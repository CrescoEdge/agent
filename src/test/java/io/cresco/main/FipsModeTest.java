package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The agent's crypto set-up (CryptoBoot): which modules serve crypto, FIPS approved-only mode, and its proofs. FIPS mode
 * changes the whole JVM's providers, so it runs in JVMs of their own (FipsProbe, the agent's --crypto tool).
 */
class FipsModeTest {

    static String run(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(out);
        }
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        String s = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), s);
        return s;
    }

    @Test
    void productionRunsFipsApprovedOnlyAndRefusesANonApprovedAlgorithm() throws Exception {
        String out = run(List.of("io.cresco.main.FipsProbe"));
        assertTrue(out.contains("fips=true"), out);
        assertTrue(out.contains("md5=refused"), "a non-approved algorithm is refused: " + out);
        assertTrue(out.contains("providers=BCFIPS,CrescoFIPS,SunJSSE") || out.contains("providers=CrescoACCP-FIPS,BCFIPS,CrescoFIPS,SunJSSE"),
                "only the FIPS modules (and the TLS engine): " + out);
        assertTrue(out.contains("approvedOnly=true"), out);
        assertTrue(out.contains("tls=SunJSSE"), out);
        assertTrue(out.contains("fipsTls=TLSv1.3"), out);
        assertTrue(out.matches("(?s).*exports=\\d+ cert jsse\n.*"), "the system bundle exports the modules' packages (no uses:): " + out);
        assertTrue(out.contains("emptyPwKey=true"), out);
        assertFalse(out.contains("weak password"), "no BC-FJA weak-password warning for a key set without one: " + out);
    }

    @Test
    void bcjsseIsTheStrictTlsChoice() throws Exception {
        String out = run(List.of("io.cresco.main.FipsProbe", "--bcjsse"));
        assertTrue(out.contains("md5=refused"), out);
        assertTrue(out.contains("tls=BCJSSE"), out);
        assertTrue(out.contains("fipsTls=TLSv1.3,TLSv1.2"), "BCJSSE's TLS 1.2 key derivation is the module's: " + out);
    }

    @Test
    void theAgentsCryptoToolReportsStandardAndFipsMode() throws Exception {
        String std = run(List.of("io.cresco.main.AgentEngine", "--crypto", "status"));
        assertTrue(std.contains("JDK providers (standard)"), std);
        assertTrue(std.contains("SunJCE"), "standard keeps the JDK's providers: " + std);
        String fips = run(List.of("io.cresco.main.AgentEngine", "--crypto", "status", "--fips"));
        assertTrue(fips.contains("FIPS 140-3 approved-only mode"), fips);
        assertTrue(fips.contains("MD5 refused"), fips);
        assertTrue(fips.contains("BC-FJA 2.1"), fips);
    }

    static X509Certificate selfSigned(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        org.bouncycastle.asn1.x500.X500Name n = new org.bouncycastle.asn1.x500.X500Name("CN=fips-test");
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(n, BigInteger.ONE, new Date(now - 60_000),
                        new Date(now + 3_600_000), n, kp.getPublic())
                        .build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA384withRSA").build(kp.getPrivate())));
    }

    @Test
    void aPkcs12KeystoreIsRefusedInFipsModeAndItsBcfksConversionLoads(@TempDir Path dir) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(3072);
        KeyPair kp = g.generateKeyPair();
        String pw = "test-only-password-" + Long.toHexString(System.nanoTime());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("node", kp.getPrivate(), pw.toCharArray(), new Certificate[]{selfSigned(kp)});
        Path p12 = dir.resolve("node.p12"), pass = dir.resolve("node.pass"), bcfks = dir.resolve("node.bcfks");
        try (OutputStream o = Files.newOutputStream(p12)) {
            ks.store(o, pw.toCharArray());
        }
        Files.writeString(pass, pw + "\n");

        String refused = run(List.of("io.cresco.main.FipsProbe", p12.toString(), pass.toString()));
        assertTrue(refused.contains("keystore=refused a PKCS#12 keystore"), refused);
        assertTrue(refused.contains("--crypto convert-keystore"), "the refusal says how to convert: " + refused);

        String conv = run(List.of("io.cresco.main.AgentEngine", "--crypto", "convert-keystore", p12.toString(), pass.toString(), bcfks.toString()));
        assertTrue(conv.contains("1 key entry"), conv);
        String loaded = run(List.of("io.cresco.main.FipsProbe", bcfks.toString(), pass.toString()));
        assertTrue(loaded.contains("keystore=loaded CrescoFIPS key=true"), "a BCFKS keystore loads under the PKCS12 name: " + loaded);
    }

    @Test
    void theRulesOfTheSetUp() {
        assertEquals("linux-x86_64", CryptoBoot.accpClassifier("Linux", "amd64"));
        assertEquals("linux-aarch_64", CryptoBoot.accpClassifier("Linux", "aarch64"));
        assertNull(CryptoBoot.accpClassifier("Mac OS X", "aarch64"), "no ACCP-FIPS build for macOS");
        assertTrue(CryptoBoot.forThisPlatform("bc-fips-2.1.3.jar", null));
        assertTrue(CryptoBoot.forThisPlatform("AmazonCorrettoCryptoProvider-FIPS-2.5.0-linux-x86_64.jar", "linux-x86_64"));
        assertFalse(CryptoBoot.forThisPlatform("AmazonCorrettoCryptoProvider-FIPS-2.5.0-linux-aarch_64.jar", "linux-x86_64"));
        assertFalse(CryptoBoot.forThisPlatform("AmazonCorrettoCryptoProvider-FIPS-2.5.0-linux-x86_64.jar", null));

        String list = "aa  controller.jar\nbb  crypto/bc-fips-2.1.3.jar\ncc  crypto/x/y.jar\ndd  crypto/notes.txt\n";
        assertEquals(java.util.Map.of("bc-fips-2.1.3.jar", "bb"), CryptoBoot.listedModules(list), "only crypto/<jar> entries");

        assertEquals(List.of("org.bouncycastle.asn1;version=\"2.1.3\"", "org.bouncycastle.cert;version=\"2.1.13\"", "org.bouncycastle.util"),
                CryptoBoot.exportClauses("org.bouncycastle.asn1;version=\"2.1.3\";uses:=\"org.bouncycastle.util,org.x\","
                        + "org.bouncycastle.cert;uses:=\"a\";version=\"2.1.13\",org.bouncycastle.util"));

        CryptoBoot.Settings s = new CryptoBoot.Settings();
        assertFalse(CryptoBoot.fipsWanted(s), "standard by default");
        s.securityProfile = "production";
        assertTrue(CryptoBoot.fipsWanted(s), "production is FIPS");
        s.fipsMode = "false";
        assertThrows(CryptoBoot.Refused.class, () -> CryptoBoot.fipsWanted(s), "production without FIPS is refused");
        s.securityProfile = "standard";
        assertFalse(CryptoBoot.fipsWanted(s));
        s.fipsMode = "true";
        assertTrue(CryptoBoot.fipsWanted(s), "FIPS asked for in the standard profile");
        s.fipsMode = "maybe";
        assertThrows(CryptoBoot.Refused.class, () -> CryptoBoot.fipsWanted(s));
        assertTrue(FipsProviders.KeyStores.isPkcs12(new byte[]{0x30, (byte) 0x82, 0x01, 0x00, 0x02, 0x01, 0x03, 0x30}));
        assertFalse(FipsProviders.KeyStores.isPkcs12(new byte[]{0x30, (byte) 0x82, 0x01, 0x00, 0x30, 0x01, 0x03, 0x30}));
    }
}
