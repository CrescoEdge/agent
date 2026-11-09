package io.cresco.main;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.security.Security;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FIPS approved-only mode ({@link CryptoBoot#configure}): the JVM's providers become the FIPS modules, and only them.
 *
 * <p>The provider list, in order:
 * <ol>
 *   <li>{@code CrescoACCP-FIPS} (Linux x86_64/aarch64, unless {@code crypto_fips_provider=bcfips}): the approved services
 *       of ACCP-FIPS (AWS-LC FIPS 3.0.0), nothing else ({@link #ACCP_APPROVED}; ACCP also offers MD5, HMAC-MD5, SHA-1
 *       signatures, Ed25519 and more, which are not taken), and not its random numbers: BC-FJA accepts only its own DRBG
 *       where an operation takes one;</li>
 *   <li>{@code BCFIPS}: BC-FJA in approved-only mode, for everything else (X.509, PKIX, BCFKS keystores, PBKDF2,
 *       AES-CBC ...);</li>
 *   <li>{@code BCJSSE} over BCFIPS, only with {@code crypto_fips_tls=bcjsse};</li>
 *   <li>{@code SunJSSE}: the TLS protocol engine (by default), which has no algorithm of its own that TLS 1.3 uses: its
 *       ciphers, key agreement, signatures, HMACs and random numbers come from the modules above;</li>
 *   <li>{@code CrescoFIPS}: the PKCS12 keystore name served by BCFKS ({@link KeyStores}), {@code DRBG} as the first
 *       module's DRBG, and the operating system's entropy as the seed source the DRBGs are seeded from
 *       ({@code securerandom.strongAlgorithms}).</li>
 * </ol>
 * Every other provider (SUN, SunRsaSign, SunEC, SunJCE, SunPKCS11, XMLDSig, Apple ...) is removed, so a non-approved
 * algorithm (MD5, DES, RC4, ChaCha20, X25519 ...) is not available at all; the agent refuses to start unless MD5 is
 * refused. With SunJSSE, TLS is TLS 1.3 only ({@code jdk.tls.server.protocols}, {@code jdk.tls.client.protocols}): its TLS
 * 1.2 key derivation is SunJCE's.
 */
final class FipsProviders {

    private FipsProviders() {}

    /** The JCA engine type of key derivation functions (HKDF). */
    static final String KDF = "SecretKeyFactory";

    /** ACCP-FIPS services taken: FIPS 140-3 approved algorithms (SP 800-131A rev. 2), nothing else. */
    static final String[][] ACCP_APPROVED = {
            {"Cipher", "AES/GCM/NoPadding"}, {"Cipher", "AES_128/GCM/NoPadding"}, {"Cipher", "AES_256/GCM/NoPadding"},
            {"Cipher", "AES/KWP/NoPadding"},
            {"KeyAgreement", "ECDH"},
            {"KeyFactory", "EC"}, {"KeyFactory", "RSA"},
            {"KeyGenerator", "AES"},
            {"KeyPairGenerator", "EC"}, {"KeyPairGenerator", "RSA"},
            {"Mac", "HmacSHA256"}, {"Mac", "HmacSHA384"}, {"Mac", "HmacSHA512"},
            {"MessageDigest", "SHA-256"}, {"MessageDigest", "SHA-384"}, {"MessageDigest", "SHA-512"},
            {KDF, "HkdfWithHmacSHA256"}, {KDF, "HkdfWithHmacSHA384"}, {KDF, "HkdfWithHmacSHA512"},
            {"Signature", "SHA256withECDSA"}, {"Signature", "SHA384withECDSA"}, {"Signature", "SHA512withECDSA"},
            {"Signature", "SHA256withRSA"}, {"Signature", "SHA384withRSA"}, {"Signature", "SHA512withRSA"},
            {"Signature", "RSASSA-PSS"},
    };

    /** Common aliases of the taken services (JCA matches names case-insensitively, aliases exactly). */
    static List<String> aliases(String type, String alg) {
        switch (type + "." + alg) {
            case "MessageDigest.SHA-256": return List.of("SHA256", "2.16.840.1.101.3.4.2.1");
            case "MessageDigest.SHA-384": return List.of("SHA384", "2.16.840.1.101.3.4.2.2");
            case "MessageDigest.SHA-512": return List.of("SHA512", "2.16.840.1.101.3.4.2.3");
            case "Signature.SHA256withECDSA": return List.of("1.2.840.10045.4.3.2");
            case "Signature.SHA384withECDSA": return List.of("1.2.840.10045.4.3.3");
            case "Signature.SHA512withECDSA": return List.of("1.2.840.10045.4.3.4");
            case "Signature.SHA256withRSA": return List.of("1.2.840.113549.1.1.11");
            case "Signature.SHA384withRSA": return List.of("1.2.840.113549.1.1.12");
            case "Signature.SHA512withRSA": return List.of("1.2.840.113549.1.1.13");
            case "Signature.RSASSA-PSS": return List.of("1.2.840.113549.1.1.10");
            case "KeyFactory.EC": case "KeyPairGenerator.EC": return List.of("1.2.840.10045.2.1");
            case "KeyFactory.RSA": case "KeyPairGenerator.RSA": return List.of("1.2.840.113549.1.1.1");
            case "Mac.HmacSHA256": return List.of("1.2.840.113549.2.9");
            case "Mac.HmacSHA384": return List.of("1.2.840.113549.2.10");
            case "Mac.HmacSHA512": return List.of("1.2.840.113549.2.11");
            default: return null;
        }
    }

    /** A service that hands out another provider's implementation, under this provider. */
    static final class Delegating extends Provider.Service {
        private final Provider.Service to;

        Delegating(Provider owner, Provider.Service to, String algorithm, List<String> aliases) {
            super(owner, to.getType(), algorithm, to.getClassName(), aliases, null);
            this.to = to;
        }

        @Override
        public Object newInstance(Object param) throws NoSuchAlgorithmException {
            return to.newInstance(param);
        }

        @Override
        public boolean supportsParameter(Object param) {
            return to.supportsParameter(param);
        }
    }

    /** The approved services of ACCP-FIPS ({@link #ACCP_APPROVED}). */
    static final class AccpApproved extends Provider {
        AccpApproved(Provider accp) {
            super("CrescoACCP-FIPS", "2.5", "ACCP-FIPS " + accp.getVersionStr() + " (AWS-LC FIPS 140-3 module): approved services only");
            for (String[] a : ACCP_APPROVED) {
                Provider.Service s = accp.getService(a[0], a[1]);
                if (s != null) putService(new Delegating(this, s, a[1], aliases(a[0], a[1])));
            }
        }
    }

    /** {@code CrescoFIPS}: PKCS12 as BCFKS, DRBG as the first module's DRBG, and the OS entropy the DRBGs seed from. */
    static final class CrescoFips extends Provider {
        CrescoFips(Provider bcfips, Provider.Service drbg, SecureRandom entropy) {
            super("CrescoFIPS", "1.3", "Cresco FIPS mode: PKCS12 keystores as BC-FJA BCFKS; DRBG; OS entropy for seeding");
            putService(new Provider.Service(this, "KeyStore", "PKCS12", KeyStores.class.getName(), List.of("PKCS#12"), null) {
                @Override public Object newInstance(Object p) { return new KeyStores(bcfips); }
            });
            if (drbg != null) putService(new Delegating(this, drbg, "DRBG", null));
            putService(new Provider.Service(this, "SecureRandom", ENTROPY, OsEntropy.class.getName(), null, null) {
                @Override public Object newInstance(Object p) { return new OsEntropy(entropy); }
            });
        }
    }

    static final String ENTROPY = "OSEntropy";

    /** The operating system's entropy (the platform's NativePRNG, taken before SUN is removed): the DRBGs' seed source. */
    static final class OsEntropy extends SecureRandomSpi {
        private final SecureRandom src;

        OsEntropy(SecureRandom src) { this.src = src; }

        @Override protected void engineSetSeed(byte[] seed) { /* entropy is not seeded */ }
        @Override protected void engineNextBytes(byte[] bytes) { src.nextBytes(bytes); }
        @Override protected byte[] engineGenerateSeed(int n) { return src.generateSeed(n); }
    }

    /**
     * The PKCS12 keystore name in FIPS mode: a BC-FJA BCFKS keystore (approved: PBKDF2 key protection, AES-CCM, HMAC
     * integrity). It loads BCFKS files; a PKCS#12 file is refused with how to convert it (its integrity MAC's key
     * derivation is not approved, so the approved-only module cannot check it). It stores BCFKS.
     */
    static final class KeyStores extends KeyStoreSpi {
        private final Provider bcfips;
        private KeyStore ks;

        KeyStores(Provider bcfips) { this.bcfips = bcfips; }

        private KeyStore ks() {
            if (ks == null) {
                try {
                    KeyStore k = KeyStore.getInstance("BCFKS", bcfips);
                    k.load(null, null);
                    ks = k;
                } catch (Exception e) {
                    throw new IllegalStateException("cannot create a BCFKS keystore: " + e, e);
                }
            }
            return ks;
        }

        static boolean isPkcs12(byte[] b) {
            // PFX ::= SEQUENCE { version INTEGER (3), authSafe ContentInfo, macData ... }: 30 <len> 02 01 03
            int i = 0;
            if (b.length < 8 || (b[i++] & 0xff) != 0x30) return false;
            int l = b[i++] & 0xff;
            if ((l & 0x80) != 0) i += l & 0x7f;
            return i + 3 <= b.length && b[i] == 0x02 && b[i + 1] == 0x01 && b[i + 2] == 0x03;
        }

        @Override
        public void engineLoad(InputStream in, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
            KeyStore k;
            try {
                k = KeyStore.getInstance("BCFKS", bcfips);
            } catch (KeyStoreException e) {
                throw new IOException(e);
            }
            if (in == null) {
                k.load(null, password);
                ks = k;
                return;
            }
            byte[] b = in.readAllBytes();
            if (isPkcs12(b)) {
                throw new IOException("a PKCS#12 keystore: in FIPS approved-only mode keystores are BCFKS (the FIPS module's own format; "
                        + "the PKCS#12 integrity check derives its key in a way FIPS does not approve). Convert it, with the same password: "
                        + "java -jar agent.jar --crypto convert-keystore <keystore.p12> <password-file> <keystore.bcfks>");
            }
            k.load(new ByteArrayInputStream(b), password);
            ks = k;
        }

        @Override
        public void engineStore(OutputStream out, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
            try {
                ks().store(out, password);
            } catch (KeyStoreException e) {
                throw new IOException(e);
            }
        }

        @Override
        public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
            try { return ks().getKey(alias, password); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            try { return ks().getCertificateChain(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            try { return ks().getCertificate(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            try { return ks().getCreationDate(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) throws KeyStoreException {
            ks().setKeyEntry(alias, key, password, chain);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException {
            ks().setKeyEntry(alias, key, chain);
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
            ks().setCertificateEntry(alias, cert);
        }

        @Override
        public void engineDeleteEntry(String alias) throws KeyStoreException {
            ks().deleteEntry(alias);
        }

        @Override
        public Enumeration<String> engineAliases() {
            try { return ks().aliases(); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            try { return ks().containsAlias(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public int engineSize() {
            try { return ks().size(); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            try { return ks().isKeyEntry(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            try { return ks().isCertificateEntry(alias); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            try { return ks().getCertificateAlias(cert); } catch (KeyStoreException e) { throw new IllegalStateException(e); }
        }
    }

    // ------------------------------------------------------------------------------------------------- install

    /** The providers kept from the JDK in FIPS mode: the TLS protocol engine only. */
    static final Set<String> KEPT = Set.of("SunJSSE");

    static CryptoBoot.Status install(CryptoBoot.Settings s) {
        String want = s.fipsProvider == null || s.fipsProvider.isBlank() ? "auto" : s.fipsProvider.trim().toLowerCase(Locale.ROOT);
        if (!want.equals("auto") && !want.equals("accp") && !want.equals("bcfips")) {
            throw new CryptoBoot.Refused("crypto_fips_provider=" + s.fipsProvider + " is not auto, accp or bcfips");
        }
        String tls = s.fipsTls == null || s.fipsTls.isBlank() ? "sunjsse" : s.fipsTls.trim().toLowerCase(Locale.ROOT);
        if (!tls.equals("sunjsse") && !tls.equals("bcjsse")) {
            throw new CryptoBoot.Refused("crypto_fips_tls=" + s.fipsTls + " is not sunjsse or bcjsse");
        }
        if (!CryptoBoot.loadable(CryptoBoot.BC_FIPS_PROVIDER)) {
            throw new CryptoBoot.Refused("FIPS mode needs Bouncy Castle FIPS (bc-fips) on the class path, and it is not");
        }
        // approved-only, JVM-wide, before the module initialises
        System.setProperty("org.bouncycastle.fips.approved_only", "true");
        SecureRandom entropy;
        try {
            entropy = System.getProperty("os.name", "").startsWith("Windows")
                    ? SecureRandom.getInstance("Windows-PRNG", "SunMSCAPI") : SecureRandom.getInstance("NativePRNGBlocking", "SUN");
        } catch (Exception e) {
            throw new CryptoBoot.Refused("no operating-system entropy source for the FIPS module's DRBG: " + e, e);
        }
        List<String> notes = new ArrayList<>();
        Provider accp = null;
        String accpInfo = null;
        if (!want.equals("bcfips")) {
            String why = null;
            if (CryptoBoot.accpClassifier(System.getProperty("os.name"), System.getProperty("os.arch")) == null) {
                why = "no ACCP-FIPS build for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
            } else if (!CryptoBoot.loadable(CryptoBoot.ACCP_PROVIDER)) {
                why = "ACCP-FIPS is not on the class path";
            } else {
                try {
                    Provider p = (Provider) Class.forName(CryptoBoot.ACCP_PROVIDER, true, ClassLoader.getSystemClassLoader())
                            .getField("INSTANCE").get(null);
                    Object loadErr = p.getClass().getMethod("getLoadingError").invoke(p);
                    if (loadErr != null) throw new IllegalStateException("it did not load: " + loadErr);
                    p.getClass().getMethod("assertHealthy").invoke(p);
                    if (!Boolean.TRUE.equals(p.getClass().getMethod("isFips").invoke(p))) throw new IllegalStateException("it is not the FIPS build");
                    accp = p;
                    accpInfo = "ACCP-FIPS " + p.getVersionStr() + " (" + accpModule(p) + ", CMVP #4816)";
                } catch (Exception e) {
                    Throwable c = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
                    why = "ACCP-FIPS is not usable: " + c;
                }
            }
            if (why != null) {
                if (want.equals("accp")) throw new CryptoBoot.Refused("crypto_fips_provider=accp: " + why);
                notes.add("WARNING " + why + ": BC-FJA serves all crypto, and TLS throughput is far lower (set crypto_fips_provider=bcfips to "
                        + "choose this explicitly)");
            }
        }
        Provider bcfips;
        try {
            bcfips = (Provider) Class.forName(CryptoBoot.BC_FIPS_PROVIDER, true, ClassLoader.getSystemClassLoader()).getConstructor().newInstance();
            Class<?> reg = Class.forName("org.bouncycastle.crypto.CryptoServicesRegistrar", true, ClassLoader.getSystemClassLoader());
            if (!Boolean.TRUE.equals(reg.getMethod("isInApprovedOnlyMode").invoke(null))) {
                throw new IllegalStateException("BC-FJA is not in approved-only mode");
            }
        } catch (CryptoBoot.Refused r) {
            throw r;
        } catch (Exception e) {
            throw new CryptoBoot.Refused("BC-FJA (bc-fips) did not start: " + e, e);
        }
        Provider bcjsse = null;
        if (tls.equals("bcjsse")) {
            try {
                bcjsse = (Provider) Class.forName(CryptoBoot.BC_JSSE_PROVIDER, true, ClassLoader.getSystemClassLoader())
                        .getConstructor(boolean.class, Provider.class).newInstance(true, bcfips);
            } catch (Exception e) {
                throw new CryptoBoot.Refused("crypto_fips_tls=bcjsse: BCJSSE (bctls-fips) did not start: " + e, e);
            }
        }
        Provider first = accp != null ? new AccpApproved(accp) : bcfips;
        // random numbers are BC-FJA's DRBG, ACCP's not taken: BC-FJA accepts only its own DRBG where an operation takes one
        // (a DH key pair made with ACCP's was refused as "unapproved RNG"), and ACCP uses AWS-LC's own DRBG inside
        Provider.Service drbg = bcfips.getService("SecureRandom", "DEFAULT");

        // the new list: the modules, the TLS engine, Cresco's names; nothing else
        Provider sunJsse = Security.getProvider("SunJSSE");
        if (sunJsse == null) throw new CryptoBoot.Refused("the JDK has no SunJSSE provider");
        for (Provider p : Security.getProviders()) {
            if (!KEPT.contains(p.getName())) Security.removeProvider(p.getName());
        }
        Security.removeProvider("SunJSSE");
        List<Provider> order = new ArrayList<>();
        if (accp != null) order.add(first);
        order.add(bcfips);
        if (bcjsse != null) order.add(bcjsse);
        // before SunJSSE, whose PKCS12 name is the JDK's PKCS#12 (SunJCE's key protection, gone): PKCS12 is BCFKS
        order.add(new CrescoFips(bcfips, drbg, entropy));
        order.add(sunJsse);
        for (int i = 0; i < order.size(); i++) Security.insertProviderAt(order.get(i), i + 1);
        Security.setProperty("securerandom.strongAlgorithms", ENTROPY + ":CrescoFIPS");
        if (bcjsse != null) {
            Security.setProperty("ssl.KeyManagerFactory.algorithm", "PKIX");
        } else {
            // SunJSSE's TLS 1.2 key derivation is SunJCE's, which is gone: TLS 1.3 only
            if (System.getProperty("jdk.tls.server.protocols") == null) System.setProperty("jdk.tls.server.protocols", "TLSv1.3");
            if (System.getProperty("jdk.tls.client.protocols") == null) System.setProperty("jdk.tls.client.protocols", "TLSv1.3");
        }
        Security.setProperty("ssl.TrustManagerFactory.algorithm", "PKIX");
        if (System.getProperty("jdk.tls.namedGroups") == null) {
            System.setProperty("jdk.tls.namedGroups", "secp384r1,secp256r1,secp521r1");
        }
        System.setProperty("cresco.crypto.fips", "true");
        System.setProperty("cresco.crypto.fips.tls", tls.equals("bcjsse") ? "TLSv1.3,TLSv1.2" : "TLSv1.3");

        // proof: the modules work, and a non-approved algorithm is refused
        List<String> problems = selfCheck();
        if (!problems.isEmpty()) throw new CryptoBoot.Refused("FIPS mode self-check failed: " + String.join("; ", problems));
        String info = bcfips.getInfo();
        String bcInfo = "BC-FJA " + (info.lastIndexOf(" v") > 0 ? info.substring(info.lastIndexOf(" v") + 2) : bcfips.getVersionStr())
                + " (bc-fips, approved-only, CMVP #4943)";
        String summary = "FIPS 140-3 approved-only mode: " + (accp != null ? accpInfo + " for its approved services, then " : "")
                + bcInfo + "; TLS " + (bcjsse != null ? "BCJSSE (bctls-fips) TLS 1.3/1.2" : "SunJSSE TLS 1.3 over the modules")
                + "; keystores BCFKS; providers " + CryptoBoot.names(Security.getProviders()) + "; " + served()
                + "; MD5 refused (non-approved algorithms are not available)" + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
        return new CryptoBoot.Status(true, summary, CryptoBoot.names(Security.getProviders()));
    }

    private static String accpModule(Provider p) {
        try (InputStream in = p.getClass().getResourceAsStream("version.properties")) {
            java.util.Properties v = new java.util.Properties();
            if (in != null) v.load(in);
            return v.getProperty("awsLcVersionStr", "AWS-LC FIPS").replace("AWS-LC AWS-LC-FIPS-", "AWS-LC FIPS ");
        } catch (IOException e) {
            return "AWS-LC FIPS";
        }
    }

    /** Which provider serves the hot algorithms (for the log). */
    static String served() {
        StringBuilder b = new StringBuilder();
        try {
            b.append("AES-GCM ").append(Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName());
            b.append(", SHA-256 ").append(MessageDigest.getInstance("SHA-256").getProvider().getName());
            b.append(", random ").append(new SecureRandom().getProvider().getName());
            b.append(", TLS ").append(SSLContext.getInstance("TLS").getProvider().getName());
        } catch (Exception e) {
            b.append(" (").append(e).append(')');
        }
        return b.toString();
    }

    /** Algorithms FIPS does not approve, beyond MD5, that must not be available. */
    static final String[][] NON_APPROVED = {{"Cipher", "ChaCha20-Poly1305"}, {"Cipher", "RC4"}, {"Cipher", "Blowfish"},
            {"KeyAgreement", "X25519"}, {"Signature", "MD5withRSA"}};

    /** What must hold in FIPS mode; empty when it does. */
    static List<String> selfCheck() {
        List<String> problems = new ArrayList<>();
        String nonApproved = CryptoBoot.nonApprovedRefusal();
        if (nonApproved != null) problems.add("a non-approved algorithm is not refused: " + nonApproved);
        for (String[] na : NON_APPROVED) {
            try {
                if (na[0].equals("Cipher")) Cipher.getInstance(na[1]);
                else if (na[0].equals("KeyAgreement")) javax.crypto.KeyAgreement.getInstance(na[1]);
                else Signature.getInstance(na[1]);
                problems.add("non-approved " + na[0] + " " + na[1] + " is available");
            } catch (Exception refused) {
                // refused, as it must be
            }
        }
        try {
            // SunJSSE still names its TLS 1.0/1.1 RSA signature; with MD5 gone it cannot be used
            Signature legacy = Signature.getInstance("MD5andSHA1withRSA");
            KeyPairGenerator r = KeyPairGenerator.getInstance("RSA");
            r.initialize(2048);
            legacy.initSign(r.generateKeyPair().getPrivate());
            legacy.update(new byte[1]);
            legacy.sign();
            problems.add("non-approved Signature MD5andSHA1withRSA works");
        } catch (Exception refused) {
            // refused, as it must be
        }
        try {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
            byte[] ct = c.doFinal("cresco".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
            d.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
            if (!"cresco".equals(new String(d.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8))) problems.add("AES-GCM round trip failed");
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair kp = g.generateKeyPair();
            Signature sig = Signature.getInstance("SHA384withECDSA");
            sig.initSign(kp.getPrivate());
            sig.update(ct);
            byte[] sg = sig.sign();
            Signature ver = Signature.getInstance("SHA384withECDSA");
            ver.initVerify(kp.getPublic());
            ver.update(ct);
            if (!ver.verify(sg)) problems.add("ECDSA P-384 signature did not verify");
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            if (!"CrescoFIPS".equals(ks.getProvider().getName())) problems.add("PKCS12 is served by " + ks.getProvider().getName());
            char[] pw = "cresco-self-check-0123456789".toCharArray();
            ks.setCertificateEntry("x", selfSignedForCheck(kp));
            ks.setKeyEntry("k", kp.getPrivate(), pw, new java.security.cert.Certificate[]{ks.getCertificate("x")});
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            ks.store(bo, pw);
            KeyStore back = KeyStore.getInstance("PKCS12");
            back.load(new ByteArrayInputStream(bo.toByteArray()), pw);
            if (back.getKey("k", pw) == null) problems.add("a keystore did not keep its key");
            SSLContext.getInstance("TLS");
        } catch (Exception e) {
            problems.add("the modules do not work: " + e);
        }
        return problems;
    }

    /** A throwaway self-signed certificate for the keystore check (Bouncy Castle, loaded only in FIPS mode). */
    private static java.security.cert.Certificate selfSignedForCheck(KeyPair kp) throws Exception {
        return CryptoTool.SelfSigned.cert(kp);
    }

    /** Names, for tests and the log. */
    static Set<String> providerNames() {
        Set<String> s = new LinkedHashSet<>();
        for (Provider p : Security.getProviders()) s.add(p.getName());
        return s;
    }
}
