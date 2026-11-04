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

    static String fingerprint(Run signed) {
        return signed.out.replaceAll("(?s).*fingerprint (sha256:[0-9a-f]{64}).*", "$1");
    }

    static Run verify(Path pub, String fp, String... rest) throws Exception {
        List<String> c = new ArrayList<>(List.of(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), "--fingerprint", fp));
        c.addAll(List.of(rest));
        return run(c.toArray(new String[0]));
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
        String fp = fingerprint(s);
        String manifest = Files.readString(dir.resolve("agent-1.3.jar.manifest"));
        assertEquals(manifest, Files.readString(dir.resolve("agent-1.3.cdx.json.manifest")), "one manifest per release");
        assertTrue(manifest.startsWith("cresco-release-manifest 1\nname agent\nversion 1.3\nsigning-key " + fp + "\n"), manifest);
        assertTrue(manifest.matches("(?s).*\nsha384 [0-9a-f]{96} agent-1\\.3\\.jar\nsha384 [0-9a-f]{96} agent-1\\.3\\.cdx\\.json\n"), manifest);
        Path pub = pub(k);
        Run v = verify(pub, fp, "--version", "1.3", "--name", "agent", jar.toString(), sbom.toString());
        assertEquals(0, v.code, v.out);
        assertTrue(v.out.contains("OK   agent-1.3.jar  (agent 1.3)"), v.out);

        // no pinned fingerprint: refused, whatever the files
        Run nopin = run(SCRIPTS.resolve("verify-release.sh").toString(), "--pub", pub.toString(), jar.toString());
        assertNotEquals(0, nopin.code);
        assertTrue(nopin.out.contains("--fingerprint sha256:<hex> is required"), nopin.out);
        // a pinned fingerprint that is not this key's
        assertNotEquals(0, verify(pub, "sha256:" + "0".repeat(64), jar.toString()).code);
        // another key, pinned by its own fingerprint (the signature is not its)
        Path otherKey = key(dir, "other.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        Run os = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", otherKey.toString(), "--out", dir.resolve("o").toString(), jar.toString());
        assertEquals(0, os.code, os.out);
        Run o = verify(pub(otherKey), fingerprint(os), jar.toString());
        assertNotEquals(0, o.code);
        assertTrue(o.out.contains("is not the key"), o.out);
        // another version than the one expected
        Run ver = verify(pub, fp, "--version", "1.4", jar.toString());
        assertNotEquals(0, ver.code);
        assertTrue(ver.out.contains("signed as agent 1.3, not version 1.4"), ver.out);
        // a changed file
        Files.writeString(sbom, "{\"bomFormat\":\"CycloneDX\",\"x\":1}");
        Run t = verify(pub, fp, jar.toString(), sbom.toString());
        assertNotEquals(0, t.code);
        assertTrue(t.out.contains("FAIL agent-1.3.cdx.json: SHA-384"), t.out);
        Files.writeString(sbom, "{\"bomFormat\":\"CycloneDX\"}");
        assertEquals(0, verify(pub, fp, sbom.toString()).code);
        // the unsigned .sha384 file is not trusted, either way
        Files.writeString(dir.resolve("agent-1.3.jar.sha384"), "0".repeat(96) + "  agent-1.3.jar\n");
        assertEquals(0, verify(pub, fp, jar.toString()).code, "the signed manifest, not the .sha384 file, decides");
        // a changed manifest (say, to list another name)
        Path m = dir.resolve("agent-1.3.jar.manifest");
        Files.writeString(m, manifest.replace("version 1.3", "version 1.4"));
        Run tm = verify(pub, fp, jar.toString());
        assertNotEquals(0, tm.code);
        assertTrue(tm.out.contains("signature of agent-1.3.jar.manifest"), tm.out);
    }

    /** The whole signed 1.2 release (manifest and signature too) served under 1.3's names, or as itself. */
    @Test
    void anOlderSignedReleaseCannotPassAsTheNewOne(@TempDir Path dir) throws Exception {
        Path k = key(dir, "release.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        Path pub = pub(k);
        Path old = Files.createDirectories(dir.resolve("1.2"));
        Path oldJar = Files.writeString(old.resolve("agent-1.2.jar"), "agent 1.2 bytes");
        Run s = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), oldJar.toString());
        assertEquals(0, s.code, s.out);
        String fp = fingerprint(s);
        Path rel = Files.createDirectories(dir.resolve("1.3"));
        Path jar = rel.resolve("agent-1.3.jar");
        Files.copy(oldJar, jar);
        for (String ext : new String[]{".sig", ".manifest", ".sha384"}) Files.copy(old.resolve("agent-1.2.jar" + ext), rel.resolve("agent-1.3.jar" + ext));
        Run v = verify(pub, fp, jar.toString());
        assertNotEquals(0, v.code, v.out);
        assertTrue(v.out.contains("does not list agent-1.3.jar"), v.out);
        Run asItself = verify(pub, fp, "--version", "1.3", oldJar.toString());
        assertNotEquals(0, asItself.code, "with --version, the old release under its own name is refused too");

        // files of two releases are never signed as one
        Run mixed = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), oldJar.toString(),
                Files.writeString(dir.resolve("agent-1.3.cdx.json"), "{}").toString());
        assertNotEquals(0, mixed.code);
        assertTrue(mixed.out.contains("sign each release separately"), mixed.out);
        // a name and version the file names cannot give must be stated
        Run unnamed = run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), Files.writeString(dir.resolve("notes.txt"), "x").toString());
        assertNotEquals(0, unnamed.code);
        assertTrue(unnamed.out.contains("give --name and --version"), unnamed.out);
        assertEquals(0, run(SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), "--name", "agent", "--version", "1.3",
                dir.resolve("notes.txt").toString()).code);
    }

    /**
     * The key's mode is read from ls -ln, which macOS, BSD and GNU/Linux print alike; stat's options differ between them
     * (GNU's stat -f reports the file system and exits 0). Simulated here with a GNU-style ls (SELinux "." suffix) and a
     * GNU-style stat ahead of the real ones on PATH.
     */
    @Test
    void theKeyModeCheckWorksWithGnuStyleTools(@TempDir Path dir) throws Exception {
        Path shim = Files.createDirectories(dir.resolve("gnu-bin"));
        String realLs = new File("/bin/ls").exists() ? "/bin/ls" : "/usr/bin/ls";
        Path ls = Files.writeString(shim.resolve("ls"), "#!/bin/sh\n" + realLs + " \"$@\" | awk '{ sub(/[@+.]$/, \"\", $1); $1 = $1 \".\"; print }'\n");
        Path stat = Files.writeString(shim.resolve("stat"), "#!/bin/sh\necho '  File: \"x\"  ID: 0 Namelen: 255 Type: ext2/ext3'\nexit 0\n");
        for (Path p : new Path[]{ls, stat}) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        Path k = key(dir, "release.key", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        Path jar = Files.writeString(dir.resolve("agent-1.3.jar"), "x");
        String path = shim + File.pathSeparator + System.getenv("PATH");

        assertEquals(0, runWithPath(path, SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), jar.toString()).code);
        Files.setPosixFilePermissions(k, PosixFilePermissions.fromString("r--------"));
        assertEquals(0, runWithPath(path, SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), jar.toString()).code);
        Files.setPosixFilePermissions(k, PosixFilePermissions.fromString("rw-r-----"));
        Run r = runWithPath(path, SCRIPTS.resolve("sign-release.sh").toString(), "--key", k.toString(), jar.toString());
        assertNotEquals(0, r.code);
        assertTrue(r.out.contains("has mode -rw-r-----."), r.out);
    }

    static Run runWithPath(String path, String... cmd) throws Exception {
        List<String> c = new ArrayList<>();
        c.add("bash");
        c.addAll(List.of(cmd));
        ProcessBuilder pb = new ProcessBuilder(c).redirectErrorStream(true);
        pb.environment().put("PATH", path);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Run(p.exitValue(), out);
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
