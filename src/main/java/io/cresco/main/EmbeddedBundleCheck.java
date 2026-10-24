package io.cresco.main;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * <p>Usage: {@code java io.cresco.main.EmbeddedBundleCheck <dir-with-jars>}; exits 1 and names
 * every offending jar when any embedded jar is not a bundle.
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

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: EmbeddedBundleCheck <dir-with-embedded-jars>");
            System.exit(2);
        }
        File dir = new File(args[0]);
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
