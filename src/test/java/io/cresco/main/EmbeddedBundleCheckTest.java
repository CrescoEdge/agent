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
}
