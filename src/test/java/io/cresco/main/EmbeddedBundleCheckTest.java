package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedBundleCheckTest {

    @TempDir
    File dir;

    /** Write a jar; {@code bsn} null = manifest without Bundle-SymbolicName, {@code withManifest} false = no manifest. */
    private File jar(String name, boolean withManifest, String bsn) throws IOException {
        File f = new File(dir, name);
        if (withManifest) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (bsn != null) {
                mf.getMainAttributes().put(new Attributes.Name("Bundle-ManifestVersion"), "2");
                mf.getMainAttributes().put(new Attributes.Name("Bundle-SymbolicName"), bsn);
            }
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(f), mf)) {
                out.putNextEntry(new ZipEntry("x/Y.class"));
                out.closeEntry();
            }
        } else {
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(f))) {
                out.putNextEntry(new ZipEntry("x/Y.class"));
                out.closeEntry();
            }
        }
        return f;
    }

    @Test
    void bundleSymbolicNameStripsDirectives() throws IOException {
        assertEquals("io.cresco.controller",
                EmbeddedBundleCheck.bundleSymbolicName(jar("a.jar", true, "io.cresco.controller;singleton:=true")));
        assertNull(EmbeddedBundleCheck.bundleSymbolicName(jar("b.jar", true, null)));
        assertNull(EmbeddedBundleCheck.bundleSymbolicName(jar("c.jar", false, null)));
        assertNull(EmbeddedBundleCheck.bundleSymbolicName(jar("d.jar", true, "  ")));
    }

    @Test
    void allBundlesPass() throws IOException {
        jar("controller.jar", true, "io.cresco.controller");
        jar("library.jar", true, "io.cresco.library");
        assertTrue(EmbeddedBundleCheck.findProblems(dir).isEmpty());
    }

    @Test
    void plainControllerJarIsNamed() throws IOException {
        jar("controller.jar", true, null);           // plain `mvn package` jar: manifest, no OSGi headers
        jar("library.jar", true, "io.cresco.library");
        jar("stripped.jar", false, null);            // manifest removed entirely
        List<String> problems = EmbeddedBundleCheck.findProblems(dir);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("controller.jar has no Bundle-SymbolicName"), problems.get(0));
        assertTrue(problems.get(1).startsWith("stripped.jar has no Bundle-SymbolicName"), problems.get(1));
    }

    @Test
    void corruptJarAndMissingControllerAreReported() throws IOException {
        java.nio.file.Files.writeString(new File(dir, "broken.jar").toPath(), "not a zip");
        List<String> problems = EmbeddedBundleCheck.findProblems(dir);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("controller.jar is missing"), problems.get(0));
        assertTrue(problems.get(1).startsWith("broken.jar is not a readable jar"), problems.get(1));
    }

    // ------------------------------------------------------------------ bundles.lock

    private File lock(String... lines) throws IOException {
        File f = new File(dir, "bundles.lock");
        java.nio.file.Files.write(f.toPath(), java.util.Arrays.asList(lines), java.nio.charset.StandardCharsets.UTF_8);
        return f;
    }

    private static String line(File jar, String bsn) throws IOException {
        return jar.getName() + " " + bsn + " 1.3.0.SNAPSHOT-20261111023154 " + EmbeddedBundleCheck.sha256(jar)
                + " central:io.cresco:x:1.3-20261111.024544-122";
    }

    @Test
    void aBuildWithExactlyTheBundlesTheLockNamesPasses() throws IOException {
        File controller = jar("controller.jar", true, "io.cresco.controller");
        File library = jar("library.jar", true, "io.cresco.library");
        jar("org.apache.felix.scr-2.2.12.jar", true, "org.apache.felix.scr");       // not a Cresco bundle: not the lock's business
        File lock = lock("# a comment", "", line(controller, "io.cresco.controller"), line(library, "io.cresco.library"));
        assertEquals(java.util.List.of(), EmbeddedBundleCheck.findLockProblems(dir, lock));
    }

    @Test
    void aBundleThatIsNotTheOneTheLockNamesFailsTheBuild() throws IOException {
        // the lock was written for one controller.jar; another is in its place (a local build left in ~/.m2, or the
        // snapshot a repository serves today): the build must not embed it silently
        File controller = jar("controller.jar", true, "io.cresco.controller");
        String locked = line(controller, "io.cresco.controller");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(controller, true)) {
            out.write(1);
        }
        java.util.List<String> problems = EmbeddedBundleCheck.findLockProblems(dir, lock(locked));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("controller.jar is not the bundle bundles.lock names"), problems.get(0));
        assertTrue(problems.get(0).contains("central:io.cresco:x:1.3-20261111.024544-122"), "the lock's origin is not named: " + problems.get(0));
    }

    @Test
    void aCrescoBundleTheLockDoesNotNameAndANamedOneThatIsAbsentFailTheBuild() throws IOException {
        File controller = jar("controller.jar", true, "io.cresco.controller");
        jar("stowaway.jar", true, "io.cresco.stowaway");
        String missing = "wsapi.jar io.cresco.wsapi 1.3.0 " + "a".repeat(64) + " committed";
        java.util.List<String> problems = EmbeddedBundleCheck.findLockProblems(dir, lock(line(controller, "io.cresco.controller"), missing));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("stowaway.jar (io.cresco.stowaway) is embedded and not named"), problems.get(0));
        assertTrue(problems.get(1).contains("names wsapi.jar, which is not embedded"), problems.get(1));
    }

    @Test
    void aLockThatCannotBeReadAsWrittenPinsNothingAndFailsTheBuild() throws IOException {
        File controller = jar("controller.jar", true, "io.cresco.controller");
        String good = line(controller, "io.cresco.controller");
        for (String[] bad : new String[][]{
                {"controller.jar io.cresco.controller 1.3.0 " + "a".repeat(64)},                    // four fields
                {"controller.jar io.cresco.controller 1.3.0 not-a-hash committed"},                 // no SHA-256
                {good, good}}) {                                                                      // named twice
            java.util.List<String> problems = EmbeddedBundleCheck.findLockProblems(dir, lock(bad));
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).startsWith("bundles.lock cannot be read"), problems.get(0));
        }
        assertTrue(EmbeddedBundleCheck.findLockProblems(dir, new File(dir, "absent.lock")).get(0).startsWith("bundles.lock cannot be read"));
    }

    @Test
    void theRepositorysOwnLockNamesItsOwnBundles() {
        // the jars committed in src/main/resources are the ones bundles.lock names (what prebuild.sh checks, in the suite)
        File resources = new File("src/main/resources"), lock = new File("bundles.lock");
        assertTrue(lock.isFile(), "bundles.lock is missing from the repository");
        assertEquals(java.util.List.of(), EmbeddedBundleCheck.findLockProblems(resources, lock));
    }
}
