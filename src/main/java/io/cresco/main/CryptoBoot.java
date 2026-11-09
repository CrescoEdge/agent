package io.cresco.main;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * The agent's cryptographic modules, and which of them serves the JVM's crypto.
 *
 * <p><b>The modules.</b> The agent jar carries, unmodified, the FIPS 140-3 validated modules and their companion jars
 * under {@code crypto/}: Bouncy Castle FIPS Java (BC-FJA, {@code bc-fips}, CMVP certificate #4943, with
 * {@code bcpkix-fips}, {@code bcutil-fips} and {@code bctls-fips}), and the Amazon Corretto Crypto Provider FIPS build
 * (ACCP-FIPS, a JCA provider over the AWS-LC FIPS module, CMVP certificate #4816) for Linux x86_64 and aarch64. A FIPS
 * module checks its own jar, so it is never repackaged into a bundle: at start ({@link #install}) each jar is written to a
 * private directory, checked against the SHA-384 the build's known-vulnerability gate recorded for it
 * ({@code META-INF/cresco/embedded.sha384}, which the release signature covers), and appended to the system class path
 * through the launcher agent ({@code Launcher-Agent-Class}, see {@link #agentmain}). The OSGi framework exports the Bouncy
 * Castle packages from there ({@link #systemPackages}); bundles import them instead of embedding Bouncy Castle.
 *
 * <p><b>Which crypto ({@link #configure}).</b>
 * <ul>
 *   <li>{@code security_profile=standard} (and {@code crypto_fips_mode} not true): the JDK's providers serve crypto and
 *       TLS as before (AES-NI and SHA intrinsics, SunJSSE). The Bouncy Castle FIPS jars are only the X.509 and ASN.1
 *       library the controller and wsapi build certificates with.</li>
 *   <li>{@code security_profile=production} (or {@code crypto_fips_mode=true}): FIPS approved-only mode. Every JCA
 *       provider that is not a FIPS module is removed; BC-FJA runs approved-only ({@code org.bouncycastle.fips.approved_only});
 *       on Linux x86_64/aarch64 ACCP-FIPS comes first, offering only its approved services ({@link FipsProviders#accp}),
 *       because it keeps TLS fast (measured: AES-256-GCM TLS 1.3 at 1.3x the JDK's speed; BC-FJA's TLS costs 2.5-4x on
 *       x86_64 even with its native code, and 30x on Apple silicon). TLS is SunJSSE, whose every cryptographic operation
 *       is then a FIPS module's, TLS 1.3 only ({@code crypto_fips_tls=bcjsse}: BCJSSE over BC-FJA, TLS 1.3 and 1.2, all of
 *       the TLS key derivation inside the module, slower). Keystores are BC-FJA's BCFKS behind the PKCS12 name
 *       ({@link FipsProviders.KeyStores}), so callers keep asking for PKCS12. A non-approved algorithm is refused: the
 *       agent checks at start that MD5 is not available, and does not start when it is. {@code crypto_fips_mode=false}
 *       with the production profile is refused, as is {@code cresco_ssl_provider=CONSCRYPT} in FIPS mode.</li>
 * </ul>
 */
public final class CryptoBoot {

    private static final Logger LOG = Logger.getLogger(CryptoBoot.class.getName());

    /** Where the modules are in the agent jar. */
    static final String MODULE_DIR = "crypto/";
    /** The gate's list of every embedded jar's SHA-384 (KnownVulnerabilityGate). */
    static final String EMBEDDED_LIST = "META-INF/cresco/embedded.sha384";

    static final String BC_FIPS_PROVIDER = "org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider";
    static final String BC_JSSE_PROVIDER = "org.bouncycastle.jsse.provider.BouncyCastleJsseProvider";
    static final String ACCP_PROVIDER = "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider";

    private static volatile Instrumentation instrumentation;
    private static volatile List<Path> modules = List.of();
    private static volatile Status status;

    private CryptoBoot() {}

    /** The launcher agent ({@code Launcher-Agent-Class}): runs before {@code main} under {@code java -jar}. */
    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** As {@link #agentmain}, for {@code -javaagent:agent.jar}. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** Thrown when the agent must not start: the crypto it was asked for cannot be had. */
    public static final class Refused extends RuntimeException {
        Refused(String msg) { super(msg); }
        Refused(String msg, Throwable cause) { super(msg, cause); }
    }

    /** What {@link #configure} set up. */
    public static final class Status {
        public final boolean fips;
        public final String summary;
        public final List<String> providers;

        Status(boolean fips, String summary, List<String> providers) {
            this.fips = fips;
            this.summary = summary;
            this.providers = List.copyOf(providers);
        }
    }

    public static Status status() { return status; }

    // ------------------------------------------------------------------------------------------------- the modules

    /** The platform's ACCP-FIPS classifier, or null where ACCP-FIPS has no build (anything but Linux x86_64/aarch64). */
    static String accpClassifier(String osName, String osArch) {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).startsWith("linux")) return null;
        String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64")) return "linux-x86_64";
        if (a.equals("aarch64") || a.equals("arm64")) return "linux-aarch_64";
        return null;
    }

    /** The {@code crypto/} entries of the gate's list: module jar name to SHA-384 (hex). */
    static Map<String, String> listedModules(String embeddedList) {
        Map<String, String> out = new LinkedHashMap<>();
        if (embeddedList == null) return out;
        for (String line : embeddedList.split("\n")) {
            int sep = line.indexOf("  ");
            if (sep <= 0) continue;
            String name = line.substring(sep + 2).trim();
            if (!name.startsWith(MODULE_DIR) || name.indexOf('/', MODULE_DIR.length()) >= 0 || !name.endsWith(".jar")) continue;
            out.put(name.substring(MODULE_DIR.length()), line.substring(0, sep).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Is this module jar for this platform (every Bouncy Castle jar; the ACCP-FIPS jar of this OS and architecture)? */
    static boolean forThisPlatform(String jar, String classifier) {
        if (!jar.startsWith("AmazonCorrettoCryptoProvider")) return true;
        return classifier != null && jar.endsWith("-" + classifier + ".jar");
    }

    /**
     * Put the agent's crypto modules on the system class path, once: each jar written, unmodified, to a new owner-only
     * directory, checked against the gate's SHA-384, then appended. When Bouncy Castle FIPS is already on the class path
     * (a deployment that puts the jars there itself) nothing is written.
     */
    public static synchronized List<Path> install() {
        if (!modules.isEmpty()) return modules;
        if (loadable(BC_FIPS_PROVIDER)) {
            // a deployment that put the jars on the class path itself: those are the modules (their exports are the
            // system bundle's, as with the agent's own copies)
            List<Path> found = new ArrayList<>();
            for (String c : MODULE_CLASSES) {
                Path p = codeSource(c);
                if (p != null && !found.contains(p)) found.add(p);
            }
            modules = List.copyOf(found);
            LOG.info("crypto: Bouncy Castle FIPS is on the class path already (" + found + "); the agent's copies are not used");
            return modules;
        }
        ClassLoader cl = CryptoBoot.class.getClassLoader();
        String list;
        try (InputStream in = cl.getResourceAsStream(EMBEDDED_LIST)) {
            list = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused("cannot read " + EMBEDDED_LIST + ": " + e.getMessage(), e);
        }
        Map<String, String> listed = listedModules(list);
        if (listed.isEmpty()) {
            throw new Refused("this agent jar lists no crypto module (" + EMBEDDED_LIST + " has no crypto/ entry): build it with mvn "
                    + "package, whose known-vulnerability gate records them");
        }
        if (instrumentation == null) {
            throw new Refused("the crypto modules cannot be put on the class path: start the agent with java -jar (its launcher agent "
                    + "appends them), or put the jars of the agent's crypto/ directory on the class path yourself");
        }
        String classifier = accpClassifier(System.getProperty("os.name"), System.getProperty("os.arch"));
        List<Path> out = new ArrayList<>();
        try {
            Path dir = privateDirectory();
            for (Map.Entry<String, String> m : listed.entrySet()) {
                if (!forThisPlatform(m.getKey(), classifier)) continue;
                byte[] bytes;
                try (InputStream in = cl.getResourceAsStream(MODULE_DIR + m.getKey())) {
                    if (in == null) throw new Refused("the agent jar lists " + MODULE_DIR + m.getKey() + " but does not carry it");
                    bytes = in.readAllBytes();
                }
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-384").digest(bytes));
                if (!actual.equals(m.getValue())) {
                    throw new Refused(MODULE_DIR + m.getKey() + " is not the jar the gate checked (SHA-384 " + actual + ", gated " + m.getValue() + ")");
                }
                Path f = dir.resolve(m.getKey());
                Files.write(f, bytes);
                f.toFile().deleteOnExit();
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(f.toFile()));
                out.add(f);
            }
            dir.toFile().deleteOnExit();
        } catch (Refused r) {
            throw r;
        } catch (Exception e) {
            throw new Refused("cannot put the crypto modules on the class path: " + e, e);
        }
        modules = List.copyOf(out);
        StringBuilder names = new StringBuilder();
        for (Path p : out) names.append(names.length() == 0 ? "" : ", ").append(p.getFileName());
        LOG.info("crypto: modules on the class path, each checked against the gate's SHA-384: " + names);
        return modules;
    }

    private static Path privateDirectory() throws IOException {
        try {
            return Files.createTempDirectory("cresco-crypto-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            return Files.createTempDirectory("cresco-crypto-");
        }
    }

    /** One class of each Bouncy Castle FIPS jar: bc-fips, bcpkix-fips, bcutil-fips, bctls-fips. */
    static final List<String> MODULE_CLASSES = List.of(BC_FIPS_PROVIDER, "org.bouncycastle.cert.X509v3CertificateBuilder",
            "org.bouncycastle.asn1.cmc.BodyPartID", BC_JSSE_PROVIDER);

    /** The jar a class is loaded from (system class loader), or null. */
    static Path codeSource(String className) {
        try {
            java.security.CodeSource cs = Class.forName(className, false, ClassLoader.getSystemClassLoader()).getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            Path p = Path.of(cs.getLocation().toURI());
            return p.getFileName().toString().endsWith(".jar") ? p : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static boolean loadable(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * The Bouncy Castle packages the system bundle exports ({@code org.osgi.framework.system.packages.extra}): every
     * package the module jars on the class path export, with its version, as their own manifests say.
     */
    public static String systemPackages() {
        Set<String> out = new LinkedHashSet<>();
        for (Path m : modules) {
            if (!m.getFileName().toString().startsWith("bc")) continue;
            try (JarFile jf = new JarFile(m.toFile(), false)) {
                String exp = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("Export-Package");
                out.addAll(exportClauses(exp));
            } catch (IOException e) {
                throw new Refused("cannot read the exports of " + m + ": " + e.getMessage(), e);
            }
        }
        return String.join(",", out);
    }

    /** {@code a;version="1";uses:="x,y",b;version="1"} as {@code a;version="1"}, {@code b;version="1"} (directives dropped). */
    static List<String> exportClauses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        List<String> clauses = new ArrayList<>();
        for (char c : header.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) {
                clauses.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        clauses.add(cur.toString());
        for (String clause : clauses) {
            String version = null;
            List<String> names = new ArrayList<>();
            for (String part : clause.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.startsWith("version=")) version = p.substring("version=".length()).replace("\"", "");
                else if (!p.contains("=")) names.add(p);
            }
            for (String n : names) out.add(version == null ? n : n + ";version=\"" + version + "\"");
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- which crypto

    /** The settings {@link #configure} reads (agent configuration: system property, CRESCO_ environment, agent.ini). */
    public static final class Settings {
        public String securityProfile;      // security_profile
        public String fipsMode;             // crypto_fips_mode: unset (production: on), true, false
        public String fipsProvider;         // crypto_fips_provider: auto (default), accp, bcfips
        public String fipsTls;              // crypto_fips_tls: sunjsse (default), bcjsse
        public String sslProvider;          // cresco_ssl_provider: JDK (default), CONSCRYPT
    }

    /** Is FIPS approved-only mode asked for? Refuses a production profile with it turned off. */
    static boolean fipsWanted(Settings s) {
        boolean production = s.securityProfile != null && s.securityProfile.trim().equalsIgnoreCase("production");
        String m = s.fipsMode == null ? "" : s.fipsMode.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty() || m.equals("auto")) return production;
        if (m.equals("true")) return true;
        if (m.equals("false")) {
            if (production) {
                throw new Refused("crypto_fips_mode=false: security_profile=production runs in FIPS approved-only mode; remove the setting");
            }
            return false;
        }
        throw new Refused("crypto_fips_mode=" + s.fipsMode + " is not true, false or auto");
    }

    /** Set up the JVM's crypto for these settings (once, before any bundle starts). */
    public static synchronized Status configure(Settings s) {
        if (status != null) return status;
        boolean fips = fipsWanted(s);
        String ssl = s.sslProvider == null ? "JDK" : s.sslProvider.trim();
        if (!fips) {
            if ("CONSCRYPT".equalsIgnoreCase(ssl)) installConscrypt();
            status = new Status(false, "JDK providers (standard): " + names(Security.getProviders())
                    + "; Bouncy Castle FIPS serves as the X.509 library only", names(Security.getProviders()));
            LOG.info("crypto: " + status.summary);
            return status;
        }
        if ("CONSCRYPT".equalsIgnoreCase(ssl)) {
            throw new Refused("cresco_ssl_provider=CONSCRYPT: FIPS mode accepts only FIPS 140-3 validated modules, and Conscrypt "
                    + "(BoringSSL) is not one; remove the setting");
        }
        status = FipsProviders.install(s);
        LOG.info("crypto: " + status.summary);
        return status;
    }

    private static void installConscrypt() {
        // TLS acceleration for the ActiveMQ inter-node broker bridge in the standard profile only (opt-in, as before)
        try {
            Provider p = (Provider) Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance();
            Security.insertProviderAt(p, 1);
            LOG.info("Installed Conscrypt (BoringSSL) as top JCE provider -- broker nio+ssl TLS accelerated");
        } catch (Throwable t) {
            LOG.warning("cresco_ssl_provider=CONSCRYPT requested but unavailable (" + t + "); falling back to JDK TLS");
        }
    }

    static List<String> names(Provider[] ps) {
        List<String> out = new ArrayList<>();
        for (Provider p : ps) out.add(p.getName());
        return out;
    }

    /** Is a non-approved algorithm refused? MD5 must not be available from any provider. */
    static String nonApprovedRefusal() {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return "MD5 is available (from " + md5.getProvider().getName() + ")";
        } catch (NoSuchAlgorithmException refused) {
            return null;
        }
    }

    /** A DRBG instance from the configured providers (for callers that only need one). */
    static SecureRandom defaultRandom() {
        return new SecureRandom();
    }
}
