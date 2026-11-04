package io.cresco.main;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** scripts/sign-release.sh and scripts/verify-release.sh, end to end with throwaway keys (skipped without bash and openssl). */
class ReleaseSigningScriptsTest {

    static final Path SCRIPTS = Paths.get("scripts").toAbsolutePath();

    record Run(int code, String out) { }

    static Run run(String... cmd) throws Exception {
        List<String> c = new ArrayList<>();
        c.add("bash");
        for (String s : cmd) c.add(s);
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Run(p.exitValue(), out);
    }

    static boolean have(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void tools() {
        assumeTrue(have("openssl"), "openssl not installed");
        assumeTrue(new File("/bin/bash").exists() || new File("/usr/bin/bash").exists(), "bash not installed");
    }

    static Path key(Path dir, String name, String... genArgs) throws Exception {
        Path k = dir.resolve(name);
        List<String> c = new ArrayList<>(List.of("openssl", "genpkey"));
        c.addAll(List.of(genArgs));
        c.addAll(List.of("-out", k.toString()));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        Files.setPosixFilePermissions(k, PosixFilePermissions.fromString("rw-------"));
        return k;
    }

    static Path pub(Path key) throws Exception {
        Path pub = key.resolveSibling(key.getFileName() + ".pub");
        Process p = new ProcessBuilder("openssl", "pkey", "-in", key.toString(), "-pubout", "-out", pub.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        return pub;
    }

    @Test
    void signThenVerifyAndEveryTamperingFails(@TempDir Path dir) throws Exception {
        Path k = key(dir, "release.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        Path jar = Files.writeString(dir.resolve("agent-1.3.jar"), "jar bytes");
        Path sbom = Files.writeString(dir.resolve("agent-1.3.cdx.json"), "{\"bomFormat\":\"CycloneDX\"}");
        Run s = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), jar.toString(), sbom.toString());
        assertEquals(0, s.code, s.out);
        assertTrue(Files.readString(dir.resolve("agent-1.3.jar.sha384")).matches("[0-9a-f]{96}  agent-1\\.3\\.jar\\n"));
        assertTrue(Files.size(dir.resolve("agent-1.3.jar.sig")) > 0);
        String fp = s.out.replaceAll("(?s).*fingerprint (sha256:[0-9a-f]{64}).*", "$1");
        Path pub = pub(k);
        Run v = run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), "--fingerprint", fp, jar.toString(), sbom.toString());
        assertEquals(0, v.code, v.out);

        // a pinned fingerprint that is not this key's
        Run pin = run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), "--fingerprint", "sha256:" + "0".repeat(64), jar.toString());
        assertNotEquals(0, pin.code);
        // another key
        Path other = pub(key(dir, "other.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384"));
        assertNotEquals(0, run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", other.toString(), jar.toString()).code);
        // a changed file
        Files.writeString(sbom, "{\"bomFormat\":\"CycloneDX\",\"x\":1}");
        Run t = run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), jar.toString(), sbom.toString());
        assertNotEquals(0, t.code);
        assertTrue(t.out.contains("FAIL agent-1.3.cdx.json"), t.out);
        // a changed checksum file (the signature still guards the content)
        Files.writeString(sbom, "{\"bomFormat\":\"CycloneDX\"}");
        Files.writeString(dir.resolve("agent-1.3.jar.sha384"), "0".repeat(96) + "  agent-1.3.jar\n");
        assertNotEquals(0, run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), jar.toString()).code);
    }

    @Test
    void weakExposedOrInRepoKeysAreRefused(@TempDir Path dir) throws Exception {
        Path jar = Files.writeString(dir.resolve("a.jar"), "x");
        Path rsa2048 = key(dir, "rsa2048.key", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048");
        Run r = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", rsa2048.toString(), jar.toString());
        assertNotEquals(0, r.code);
        assertTrue(r.out.contains("RSA-2048"), r.out);

        Path ec = key(dir, "ec.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        Files.setPosixFilePermissions(ec, PosixFilePermissions.fromString("rw-r--r--"));
        r = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", ec.toString(), jar.toString());
        assertNotEquals(0, r.code);
        assertTrue(r.out.contains("0600"), r.out);

        Path p256 = key(dir, "p256.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256");
        assertNotEquals(0, run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", p256.toString(), jar.toString()).code);

        // a key inside the repository (target/ is ignored by git, so nothing is ever committed by this test)
        Path inRepo = Files.createDirectories(Paths.get("target", "signing-test").toAbsolutePath());
        Path k = key(inRepo, "in-repo.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        try {
            r = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), jar.toString());
            assertNotEquals(0, r.code);
            assertTrue(r.out.contains("inside the repository"), r.out);
        } finally {
            Files.deleteIfExists(k);
        }
        assertFalse(Files.exists(dir.resolve("a.jar.sig")), "nothing was signed");
    }
}
