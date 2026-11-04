package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Adversarial review (review/api-20261104) of the release signing scripts and the embedded-jar SBOM. */
class ReviewApiSupplyChainTest {

    static final Path SCRIPTS = Paths.get("scripts").toAbsolutePath();

    static int run(StringBuilder out, String... cmd) throws Exception {
        List<String> c = new ArrayList<>();
        c.add("bash");
        c.addAll(List.of(cmd));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getOutputStream().close();
        out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return p.exitValue();
    }

    static void openssl(String... args) throws Exception {
        List<String> c = new ArrayList<>(List.of("openssl"));
        c.addAll(List.of(args));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    /**
     * The signature covers the bytes of a file, never its name or version, and the .sha384 file that names it is not
     * signed. Whoever controls the download location serves an older, properly signed agent jar (one with a known
     * vulnerability) under the new release's name with a fresh .sha384, and verify-release.sh, pinned to the real key,
     * accepts it as the new release.
     */
    @Test
    void anOlderSignedReleaseDoesNotVerifyUnderTheNewReleasesName(@TempDir Path dir) throws Exception {
        assumeTrue(new File("/bin/bash").exists() || new File("/usr/bin/bash").exists());
        Path key = dir.resolve("release.key");
        openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384", "-out", key.toString());
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        Path pub = dir.resolve("release.pub");
        openssl("pkey", "-in", key.toString(), "-pubout", "-out", pub.toString());

        Path old = Files.createDirectories(dir.resolve("1.2"));
        Path oldJar = Files.writeString(old.resolve("agent-1.2.jar"), "agent 1.2 bytes, with the bug 1.3 fixed");
        StringBuilder out = new StringBuilder();
        assertEquals(0, run(out, SCRIPTS.resolve("sign-release.sh").toString(), "--key", key.toString(), oldJar.toString()), out.toString());
        String fp = out.toString().replaceAll("(?s).*fingerprint (sha256:[0-9a-f]{64}).*", "$1");

        // the attacker's "1.3" directory: 1.2's jar and signature under 1.3's names, and a checksum file they wrote
        Path rel = Files.createDirectories(dir.resolve("1.3"));
        Path jar = rel.resolve("agent-1.3.jar");
        Files.copy(oldJar, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(old.resolve("agent-1.2.jar.sig"), rel.resolve("agent-1.3.jar.sig"));
        String sum = Files.readString(old.resolve("agent-1.2.jar.sha384")).split(" ")[0];
        Files.writeString(rel.resolve("agent-1.3.jar.sha384"), sum + "  agent-1.3.jar\n");

        StringBuilder v = new StringBuilder();
        int code = run(v, SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), "--fingerprint", fp, jar.toString());
        assertNotEquals(0, code, "the 1.2 jar verified as agent-1.3.jar with the pinned key:\n" + v);
    }

    /**
     * The SBOM lists an embedded jar's nested jars and the projects named by its pom.properties files, but classes a
     * bundle inlines without a pom.properties are not listed at all. The agent's own library.jar inlines LMAX Disruptor,
     * Dropwizard Metrics and ANTLR 4 this way (from siddhi-core-nolog4j); none of them is in target/agent-*.cdx.json,
     * so a scanner reading the SBOM never sees their CVEs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void classesInlinedWithoutPomPropertiesAreStillAccountedFor(@TempDir Path dir) throws Exception {
        Path bom = dir.resolve("agent.cdx.json");
        Files.writeString(bom, "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{\"component\":"
                + "{\"bom-ref\":\"pkg:maven/io.cresco/agent@1.3\",\"name\":\"agent\"}},\"components\":[],\"dependencies\":[]}");
        Path jars = Files.createDirectories(dir.resolve("classes"));
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Bundle-SymbolicName", "io.cresco.library");
        mf.getMainAttributes().putValue("Bundle-Version", "1.3.0");
        try (OutputStream fo = Files.newOutputStream(jars.resolve("library.jar")); JarOutputStream jo = new JarOutputStream(fo, mf)) {
            jo.putNextEntry(new JarEntry("META-INF/maven/io.cresco/library/pom.properties"));
            jo.write("groupId=io.cresco\nartifactId=library\nversion=1.3-SNAPSHOT\n".getBytes(StandardCharsets.UTF_8));
            jo.closeEntry();
            for (String cls : new String[]{"io/cresco/library/plugin/PluginBuilder.class", "com/lmax/disruptor/RingBuffer.class",
                    "com/codahale/metrics/MetricRegistry.class", "org/antlr/v4/runtime/Parser.class"}) {
                jo.putNextEntry(new JarEntry(cls));
                jo.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                jo.closeEntry();
            }
        }
        EmbeddedSbom.complete(bom, jars.toFile());
        String text = Files.readString(bom);
        List<String> missing = new ArrayList<>();
        for (String pkg : new String[]{"com.lmax.disruptor", "com.codahale.metrics", "org.antlr.v4"}) {
            if (!text.contains(pkg) && !text.contains(pkg.replace('.', '/'))) missing.add(pkg);
        }
        assertEquals(List.of(), missing, "library.jar ships these classes, and the SBOM says nothing about them");
    }
}
