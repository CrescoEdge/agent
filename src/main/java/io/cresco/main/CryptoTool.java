package io.cresco.main;


import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code java -jar agent.jar --crypto <command>}: the agent's crypto tools.
 * <ul>
 *   <li>{@code convert-keystore <in.p12> <password-file> <out.bcfks>}: a PKCS#12 keystore as BCFKS, the keystore format
 *       of FIPS approved-only mode, same entries and password (the output is owner-only). Runs outside FIPS mode: it
 *       reads PKCS#12 with the JDK and writes BCFKS with BC-FJA.</li>
 *   <li>{@code bench [--fips] [--bcjsse] [--bcfips] [seconds]}: the throughput of this JVM's crypto as the agent sets
 *       it up (standard, or FIPS mode with {@code --fips}): AES-256-GCM, SHA-256, ECDSA P-384 and RSA-3072 signatures
 *       (message signing), and TLS 1.3 AES-256-GCM over a pair of SSLEngines, one thread. Prints one line per measure
 *       and a JSON line ({@code scripts/bench-crypto.sh} runs both modes).</li>
 *   <li>{@code status [--fips]}: the providers and modules as the agent would set them up.</li>
 * </ul>
 */
public final class CryptoTool {

    private CryptoTool() {}

    public static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java -jar agent.jar --crypto convert-keystore <in.p12> <password-file> <out.bcfks>\n"
                    + "       java -jar agent.jar --crypto bench [--fips] [--bcjsse] [--bcfips] [seconds]\n"
                    + "       java -jar agent.jar --crypto status [--fips] [--bcjsse] [--bcfips]");
            return 2;
        }
        CryptoBoot.install();
        switch (args[1]) {
            case "convert-keystore":
                if (args.length != 5) {
                    System.err.println("usage: java -jar agent.jar --crypto convert-keystore <in.p12> <password-file> <out.bcfks>");
                    return 2;
                }
                convert(Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4]));
                return 0;
            case "bench":
            case "status": {
                CryptoBoot.Settings s = new CryptoBoot.Settings();
                double secs = 5;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--fips")) s.fipsMode = "true";
                    else if (args[i].equals("--bcjsse")) s.fipsTls = "bcjsse";
                    else if (args[i].equals("--bcfips")) s.fipsProvider = "bcfips";
                    else secs = Double.parseDouble(args[i]);
                }
                CryptoBoot.Status st = CryptoBoot.configure(s);
                System.out.println("crypto: " + st.summary);
                if (args[1].equals("bench")) bench(st, secs);
                return 0;
            }
            default:
                System.err.println("unknown crypto command " + args[1]);
                return 2;
        }
    }

    // ------------------------------------------------------------------------------------------------ convert

    static void convert(Path in, Path passFile, Path out) throws Exception {
        String pw = Files.readAllLines(passFile, StandardCharsets.UTF_8).stream().findFirst().orElse("").trim();
        if (pw.isEmpty()) throw new IllegalArgumentException(passFile + " holds no password");
        char[] p = pw.toCharArray();
        KeyStore src = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(in)) {
            src.load(is, p);
        }
        Provider bcfips = (Provider) Class.forName(CryptoBoot.BC_FIPS_PROVIDER).getConstructor().newInstance();
        KeyStore dst = KeyStore.getInstance("BCFKS", bcfips);
        dst.load(null, null);
        int keys = 0, certs = 0;
        for (String alias : Collections.list(src.aliases())) {
            if (src.isKeyEntry(alias)) {
                Key k = src.getKey(alias, p);
                Certificate[] chain = src.getCertificateChain(alias);
                dst.setKeyEntry(alias, k, p, chain);
                keys++;
            } else if (src.isCertificateEntry(alias)) {
                dst.setCertificateEntry(alias, src.getCertificate(alias));
                certs++;
            }
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        dst.store(bo, p);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(tmp);
        }
        Files.write(tmp, bo.toByteArray());
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("converted " + in + " to " + out + " (BCFKS, the same password): " + keys + " key entr" + (keys == 1 ? "y" : "ies")
                + ", " + certs + " certificate entr" + (certs == 1 ? "y" : "ies"));
    }

    // ------------------------------------------------------------------------------------------------ bench

    static void bench(CryptoBoot.Status st, double secs) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", st.fips ? "fips" : "standard");
        r.put("java", System.getProperty("java.version"));
        r.put("arch", System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        r.put("providers", String.join(" ", st.providers));
        r.put("aes_gcm_mb_s", aesGcm(secs));
        r.put("sha256_mb_s", sha256(secs));
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair eck = ec.generateKeyPair();
        r.put("ecdsa_p384_sign_per_s", signs(eck, "SHA384withECDSA", secs));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(3072);
        KeyPair rsak = rsa.generateKeyPair();
        r.put("rsa3072_sign_per_s", signs(rsak, "SHA384withRSA", secs));
        r.put("tls13_aes256gcm_mb_s", tls(eck, secs));
        for (Map.Entry<String, Object> e : r.entrySet()) System.out.println(e.getKey() + ": " + e.getValue());
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, Object> e : r.entrySet()) {
            if (json.length() > 1) json.append(", ");
            json.append('"').append(e.getKey()).append("\": ");
            if (e.getValue() instanceof Number) json.append(e.getValue());
            else json.append('"').append(String.valueOf(e.getValue()).replace("\"", "'")).append('"');
        }
        System.out.println("BENCH " + json.append('}'));
    }

    /** Runs {@code op} for about {@code secs} (after one second of warm-up) and returns operations per second. */
    private static double rate(double secs, java.util.concurrent.Callable<Long> op) throws Exception {
        long t0 = System.nanoTime(), warm = t0 + 1_000_000_000L, end = t0 + (long) ((secs + 1) * 1e9);
        long n = 0;
        boolean measuring = false;
        while (true) {
            long now = System.nanoTime();
            if (!measuring && now > warm) {
                measuring = true;
                t0 = now;
                n = 0;
            }
            if (now > end) break;
            n += op.call();
        }
        return n / ((System.nanoTime() - t0) / 1e9);
    }

    private static long aesGcm(double secs) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] buf = new byte[16384], out = new byte[16384 + 16];
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        long[] iv = {0};
        return Math.round(rate(secs, () -> {
            byte[] nonce = new byte[12];
            ByteBuffer.wrap(nonce).putLong(4, ++iv[0]);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.doFinal(buf, 0, buf.length, out, 0);
            return (long) buf.length;
        }) / 1e6);
    }

    private static long sha256(double secs) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        return Math.round(rate(secs, () -> {
            md.update(buf);
            md.digest();
            return (long) buf.length;
        }) / 1e6);
    }

    private static long signs(KeyPair kp, String alg, double secs) throws Exception {
        Signature s = Signature.getInstance(alg);
        byte[] msg = new byte[512];
        return Math.round(rate(Math.min(secs, 3), () -> {
            s.initSign(kp.getPrivate());
            s.update(msg);
            s.sign();
            return 1L;
        }));
    }

    private static long tls(KeyPair kp, double secs) throws Exception {
        X509Certificate cert = SelfSigned.cert(kp);
        char[] pw = "cresco-bench-password-0123456789".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("tls", kp.getPrivate(), pw, new Certificate[]{cert});
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("tls", cert);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLEngine srv = ctx.createSSLEngine(), cli = ctx.createSSLEngine("cresco-bench", 1);
        srv.setUseClientMode(false);
        cli.setUseClientMode(true);
        for (SSLEngine e : new SSLEngine[]{srv, cli}) {
            e.setEnabledProtocols(new String[]{"TLSv1.3"});
            e.setEnabledCipherSuites(new String[]{"TLS_AES_256_GCM_SHA384"});
        }
        int pkt = Math.max(srv.getSession().getPacketBufferSize(), cli.getSession().getPacketBufferSize());
        int app = Math.max(srv.getSession().getApplicationBufferSize(), cli.getSession().getApplicationBufferSize());
        ByteBuffer c2s = ByteBuffer.allocate(pkt * 4), s2c = ByteBuffer.allocate(pkt * 4);
        ByteBuffer cliOut = ByteBuffer.allocate(16384), srvIn = ByteBuffer.allocate(app * 4), cliIn = ByteBuffer.allocate(app * 4),
                srvOut = ByteBuffer.allocate(0);
        cli.beginHandshake();
        srv.beginHandshake();
        for (int i = 0; i < 1000 && (busy(cli) || busy(srv)); i++) {
            cliOut.clear().limit(0);
            step(cli, cliOut, c2s, cliIn, s2c);
            step(srv, srvOut, s2c, srvIn, c2s);
        }
        if (busy(cli) || busy(srv)) throw new IllegalStateException("TLS handshake did not finish: " + cli.getHandshakeStatus() + " " + srv.getHandshakeStatus());
        byte[] data = new byte[16384];
        new SecureRandom().nextBytes(data);
        return Math.round(rate(secs, () -> {
            cliOut.clear();
            cliOut.put(data).flip();
            c2s.clear();
            SSLEngineResult w = cli.wrap(cliOut, c2s);
            if (w.getStatus() != SSLEngineResult.Status.OK) throw new IllegalStateException("wrap " + w);
            c2s.flip();
            srvIn.clear();
            while (c2s.hasRemaining()) {
                SSLEngineResult u = srv.unwrap(c2s, srvIn);
                if (u.getStatus() != SSLEngineResult.Status.OK) throw new IllegalStateException("unwrap " + u);
            }
            return (long) srvIn.position();
        }) / 1e6);
    }

    private static boolean busy(SSLEngine e) {
        SSLEngineResult.HandshakeStatus h = e.getHandshakeStatus();
        return h != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && h != SSLEngineResult.HandshakeStatus.FINISHED;
    }

    private static void step(SSLEngine e, ByteBuffer appOut, ByteBuffer netOut, ByteBuffer appIn, ByteBuffer netIn) throws Exception {
        tasks(e);
        SSLEngineResult.HandshakeStatus h = e.getHandshakeStatus();
        if (h == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            e.wrap(appOut, netOut);
        } else if (h == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || h == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) {
            netIn.flip();
            e.unwrap(netIn, appIn);
            netIn.compact();
        }
        tasks(e);
    }

    private static void tasks(SSLEngine e) {
        Runnable t;
        while (e.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK && (t = e.getDelegatedTask()) != null) t.run();
    }

    /** A self-signed certificate (Bouncy Castle: a class of its own, loaded only once the modules are on the class path). */
    static final class SelfSigned {
        static X509Certificate cert(KeyPair kp) throws Exception {
            long now = System.currentTimeMillis();
            org.bouncycastle.asn1.x500.X500Name n = new org.bouncycastle.asn1.x500.X500Name("CN=cresco-bench");
            return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(n, BigInteger.valueOf(now), new Date(now - 60_000),
                            new Date(now + 3_600_000), n, kp.getPublic())
                            .addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                                    new org.bouncycastle.asn1.x509.BasicConstraints(false))
                            .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA384withECDSA").build(kp.getPrivate())));
        }
    }

    /** For a status line: the providers in order. */
    static String providers() {
        StringBuilder b = new StringBuilder();
        for (Provider p : Security.getProviders()) b.append(b.length() == 0 ? "" : " ").append(p.getName());
        return b.toString();
    }
}
