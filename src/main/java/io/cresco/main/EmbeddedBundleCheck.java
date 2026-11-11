package io.cresco.main;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Verifies that every jar embedded in the agent (src/main/resources/*.jar) is an OSGi bundle,
 * i.e. its manifest carries a Bundle-SymbolicName. The agent installs these jars into Felix at
 * startup; a jar without the OSGi manifest (for example a controller built with plain
 * {@code mvn package}, without the {@code bundle:bundle} goal) installs as an empty legacy bundle
 * that never activates, and the agent used to park silently. This check runs in the build
 * (process-classes, see pom.xml) so such a jar can never be packaged; HostApplication applies the
 * same rule at startup to the bundles Felix actually installs.
 *
 * <p>With a second argument, {@code bundles.lock}, it also verifies that every embedded Cresco bundle
 * (Bundle-SymbolicName {@code io.cresco.*}) is the one the lock names, byte for byte (SHA-256), and that every bundle
 * the lock names is there. The build used to embed whatever a snapshot repository served that day -- or a bundle a
 * local build had left in {@code ~/.m2} -- so a release could not be named or built again; now a build contains what
 * the lock says, or it fails. {@code prebuild.sh} writes the lock ({@code --update}, {@code --lock}).
 *
 * <p>Usage: {@code java io.cresco.main.EmbeddedBundleCheck <dir-with-jars> [<bundles.lock>]}; exits 1 and names
 * every offending jar.
 */
public final class EmbeddedBundleCheck {

    static final String BUNDLE_SYMBOLICNAME = "Bundle-SymbolicName";

    /** The one embedded jar the agent cannot run without. */
    static final String REQUIRED_CONTROLLER_JAR = "controller.jar";

    private EmbeddedBundleCheck() {
    }

    /**
     * Return the Bundle-SymbolicName of a jar (directives such as {@code ;singleton:=true}
     * stripped), or null when the jar has no manifest or the manifest has no Bundle-SymbolicName.
     */
    static String bundleSymbolicName(File jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar, false)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            String bsn = manifest.getMainAttributes().getValue(new Attributes.Name(BUNDLE_SYMBOLICNAME));
            if (bsn == null) {
                return null;
            }
            int semi = bsn.indexOf(';');
            if (semi >= 0) {
                bsn = bsn.substring(0, semi);
            }
            bsn = bsn.trim();
            return bsn.isEmpty() ? null : bsn;
        }
    }

    /**
     * Check every *.jar directly under {@code dir}. Returns one human-readable problem line per
     * offending jar (empty when all are bundles).
     */
    static List<String> findProblems(File dir) {
        List<String> problems = new ArrayList<>();
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) {
            problems.add(dir.getAbsolutePath() + " is not a readable directory");
            return problems;
        }
        Arrays.sort(jars);
        if (!new File(dir, REQUIRED_CONTROLLER_JAR).isFile()) {
            problems.add(REQUIRED_CONTROLLER_JAR + " is missing (run prebuild.sh or re-embed the controller)");
        }
        for (File jar : jars) {
            try {
                if (bundleSymbolicName(jar) == null) {
                    problems.add(jar.getName() + " has no " + BUNDLE_SYMBOLICNAME
                            + " in META-INF/MANIFEST.MF (not an OSGi bundle)");
                }
            } catch (IOException ex) {
                problems.add(jar.getName() + " is not a readable jar (" + ex.getMessage() + ")");
            }
        }
        return problems;
    }

    /** The prefix of the Bundle-SymbolicName of every bundle Cresco builds: those the lock must name. */
    static final String CRESCO_BUNDLE_PREFIX = "io.cresco.";

    /** One line of bundles.lock: an embedded Cresco bundle, as a build must contain it. */
    static final class Locked {
        final String file, symbolicName, version, sha256, origin;

        Locked(String file, String symbolicName, String version, String sha256, String origin) {
            this.file = file;
            this.symbolicName = symbolicName;
            this.version = version;
            this.sha256 = sha256;
            this.origin = origin;
        }
    }

    /**
     * Read bundles.lock: one bundle a line ({@code file symbolic-name bundle-version sha256 origin}, separated by
     * blanks), {@code #} comments and empty lines skipped. A line with another shape, a SHA-256 that is not one, or
     * a file named twice is an error: a lock that cannot be read as written pins nothing.
     */
    static Map<String, Locked> readLock(File lock) throws IOException {
        Map<String, Locked> out = new LinkedHashMap<>();
        int n = 0;
        for (String line : Files.readAllLines(lock.toPath(), StandardCharsets.UTF_8)) {
            n++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length != 5) throw new IOException(lock.getName() + " line " + n + ": 5 fields expected, " + f.length + " found");
            if (!f[3].matches("[0-9a-f]{64}")) throw new IOException(lock.getName() + " line " + n + ": '" + f[3] + "' is not a SHA-256");
            if (out.put(f[0], new Locked(f[0], f[1], f[2], f[3], f[4])) != null) {
                throw new IOException(lock.getName() + " line " + n + ": " + f[0] + " is named twice");
            }
        }
        return out;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Check the embedded Cresco bundles under {@code dir} against {@code lock}: one problem line for each bundle the
     * lock does not name, each whose bytes are not the ones it names, and each it names that is not there (empty when
     * the directory holds exactly what the lock says). Jars that are not Cresco bundles (the Felix and logging bundles)
     * are not the lock's business.
     */
    static List<String> findLockProblems(File dir, File lock) {
        List<String> problems = new ArrayList<>();
        Map<String, Locked> locked;
        try {
            locked = readLock(lock);
        } catch (IOException ex) {
            problems.add("bundles.lock cannot be read (" + ex.getMessage() + "): prebuild.sh --lock writes it");
            return problems;
        }
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) {
            problems.add(dir.getAbsolutePath() + " is not a readable directory");
            return problems;
        }
        Arrays.sort(jars);
        List<String> seen = new ArrayList<>();
        for (File jar : jars) {
            try {
                String bsn = bundleSymbolicName(jar);
                if (bsn == null || !bsn.startsWith(CRESCO_BUNDLE_PREFIX)) continue;
                seen.add(jar.getName());
                Locked l = locked.get(jar.getName());
                if (l == null) {
                    problems.add(jar.getName() + " (" + bsn + ") is embedded and not named in bundles.lock"
                            + " (prebuild.sh --lock " + jar.getName() + " records it)");
                    continue;
                }
                String actual = sha256(jar);
                if (!actual.equals(l.sha256)) {
                    problems.add(jar.getName() + " is not the bundle bundles.lock names: its SHA-256 is " + actual
                            + ", the lock says " + l.sha256 + " (" + l.version + ", " + l.origin + ")");
                } else if (!bsn.equals(l.symbolicName)) {
                    problems.add(jar.getName() + " is " + bsn + ", bundles.lock says " + l.symbolicName);
                }
            } catch (IOException ex) {
                problems.add(jar.getName() + " is not a readable jar (" + ex.getMessage() + ")");
            }
        }
        for (String file : locked.keySet()) {
            if (!seen.contains(file)) problems.add("bundles.lock names " + file + ", which is not embedded");
        }
        return problems;
    }

    public static void main(String[] args) {
        if (args.length != 1 && args.length != 2) {
            System.err.println("usage: EmbeddedBundleCheck <dir-with-embedded-jars> [<bundles.lock>]");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (args.length == 2) {
            List<String> lockProblems = findLockProblems(dir, new File(args[1]));
            if (!lockProblems.isEmpty()) {
                System.err.println("agent build: the embedded Cresco bundles in " + dir.getPath() + " are not what " + args[1] + " names:");
                for (String p : lockProblems) {
                    System.err.println("  - " + p);
                }
                System.err.println("agent build: a build embeds exactly the bundles the lock names. Put the named bundle back, or"
                        + " change the lock on purpose: prebuild.sh --update <name> (the latest published snapshot) or"
                        + " prebuild.sh --lock <name> (the jar as it is), and commit bundles.lock with the jar.");
                System.exit(1);
            }
            System.out.println("agent build: the embedded Cresco bundles are the ones " + args[1] + " names");
        }
        List<String> problems = findProblems(dir);
        if (!problems.isEmpty()) {
            System.err.println("agent build: embedded jar(s) in " + dir.getPath()
                    + " are not OSGi bundles and would stall agent startup:");
            for (String p : problems) {
                System.err.println("  - " + p);
            }
            System.err.println("agent build: rebuild each named component with "
                    + "'mvn clean package bundle:bundle' (never plain 'mvn package') and re-embed it.");
            System.exit(1);
        }
        System.out.println("agent build: all embedded jars in " + dir.getPath() + " are OSGi bundles");
    }
}
