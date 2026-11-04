package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** The agent's own SBOM part, written from the runtime classpath and the local repository with no Maven plugin. */
class MavenSbomTest {

    static Path jar(Path file, String pomProperties) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (OutputStream fo = Files.newOutputStream(file); JarOutputStream jo = new JarOutputStream(fo, mf)) {
            jo.putNextEntry(new JarEntry("x/A.class"));
            jo.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
            jo.closeEntry();
            if (pomProperties != null) {
                jo.putNextEntry(new JarEntry("META-INF/maven/g/a/pom.properties"));
                jo.write(pomProperties.getBytes(StandardCharsets.UTF_8));
                jo.closeEntry();
            }
        }
        return file;
    }

    static Path artifact(Path repo, String g, String a, String v, String pomBody) throws IOException {
        Path dir = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(dir);
        if (pomBody != null) {
            Files.writeString(dir.resolve(a + "-" + v + ".pom"), "<?xml version=\"1.0\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                    + "<modelVersion>4.0.0</modelVersion>" + pomBody + "</project>");
        }
        return jar(dir.resolve(a + "-" + v + ".jar"), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProjectAndItsRuntimeClasspathAreListedWithLicensesAndEdges(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo");
        artifact(repo, "org.example", "parent", "7", "<groupId>org.example</groupId><artifactId>parent</artifactId><version>7</version>"
                + "<properties><dep.group>org.example</dep.group></properties>"
                + "<licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>");
        Path a = artifact(repo, "org.example", "alpha", "1.0", "<parent><groupId>org.example</groupId><artifactId>parent</artifactId>"
                + "<version>7</version></parent><artifactId>alpha</artifactId><dependencies>"
                + "<dependency><groupId>${dep.group}</groupId><artifactId>beta</artifactId></dependency>"
                + "<dependency><groupId>${project.groupId}</groupId><artifactId>gamma</artifactId><optional>true</optional></dependency>"
                + "<dependency><groupId>org.example</groupId><artifactId>delta</artifactId><scope>test</scope></dependency>"
                + "</dependencies>");
        Path b = artifact(repo, "org.example", "beta", "2.0", "<groupId>org.example</groupId><artifactId>beta</artifactId><version>2.0</version>"
                + "<licenses><license><name>MIT</name></license></licenses>");
        Path g = artifact(repo, "org.example", "gamma", "3.0", null);                          // no POM: no licenses, no edges
        Path d = artifact(repo, "org.example", "delta", "4.0", null);                          // reached by nobody's POM
        Path snap = repo.resolve("org/example/snap/1.1-SNAPSHOT/snap-1.1-20261104.120000-3-linux.jar");
        jar(snap, null);
        Path outside = jar(dir.resolve("lib/vendored.jar"), "groupId=com.vendor\nartifactId=vendored\nversion=9\n");
        Path pom = Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>io.cresco</groupId>"
                + "<artifactId>agent</artifactId><version>1.3</version><description>The agent</description>"
                + "<licenses><license><name>Apache License, Version 2.0</name></license></licenses><dependencies>"
                + "<dependency><groupId>org.example</groupId><artifactId>alpha</artifactId></dependency>"
                + "<dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>"
                + "</dependencies></project>");

        Map<String, Object> bom = MavenSbom.create("io.cresco:agent:1.3", pom, repo,
                List.of(dir.resolve("classes"), a, b, g, d, snap, outside));
        String text = EmbeddedSbom.Json.write(bom);
        Map<String, Object> parsed = (Map<String, Object>) EmbeddedSbom.Json.parse(text);
        assertEquals("CycloneDX", parsed.get("bomFormat"));
        assertEquals("1.6", parsed.get("specVersion"));
        assertTrue(((String) parsed.get("serialNumber")).matches("urn:uuid:[0-9a-f-]{36}"));
        Map<String, Object> root = (Map<String, Object>) ((Map<String, Object>) parsed.get("metadata")).get("component");
        assertEquals("pkg:maven/io.cresco/agent@1.3", root.get("bom-ref"));
        assertEquals("The agent", root.get("description"));
        assertTrue(text.contains("Apache License, Version 2.0"));

        List<Object> comps = (List<Object>) parsed.get("components");
        assertEquals(6, comps.size(), "every jar on the runtime classpath, no directories");
        Map<String, Object> alpha = byRef(comps, "pkg:maven/org.example/alpha@1.0");
        assertEquals("org.example", alpha.get("group"));
        assertEquals("required", alpha.get("scope"));
        assertEquals(List.of("SHA-256", "SHA-384", "SHA-512"), ((List<Object>) alpha.get("hashes")).stream().map(h -> ((Map<String, Object>) h).get("alg")).toList());
        assertEquals("Apache-2.0", license(alpha), "inherited from the parent POM");
        assertEquals("MIT", license(byRef(comps, "pkg:maven/org.example/beta@2.0")), "a POM's own license");
        assertNull(byRef(comps, "pkg:maven/org.example/gamma@3.0").get("licenses"));
        Map<String, Object> s = byRef(comps, "pkg:maven/org.example/snap@1.1-SNAPSHOT?classifier=linux");
        assertEquals("1.1-SNAPSHOT", s.get("version"), "a timestamped snapshot, with its classifier");
        assertEquals("com.vendor", byRef(comps, "pkg:maven/com.vendor/vendored@9").get("group"), "outside the repository: its pom.properties");

        Map<String, List<Object>> deps = new java.util.HashMap<>();
        for (Object o : (List<Object>) parsed.get("dependencies")) {
            Map<String, Object> e = (Map<String, Object>) o;
            deps.put((String) e.get("ref"), (List<Object>) e.get("dependsOn"));
        }
        assertEquals(List.of("pkg:maven/org.example/beta@2.0"), deps.get("pkg:maven/org.example/alpha@1.0"),
                "a property-named dependency is an edge; optional and test ones are not");
        List<Object> rootOn = deps.get("pkg:maven/io.cresco/agent@1.3");
        assertEquals("pkg:maven/org.example/alpha@1.0", rootOn.get(0));
        assertTrue(rootOn.containsAll(List.of("pkg:maven/org.example/gamma@3.0", "pkg:maven/org.example/delta@4.0",
                "pkg:maven/com.vendor/vendored@9")), "nothing shipped is left unattached: " + rootOn);
        assertFalse(rootOn.contains("pkg:maven/org.example/beta@2.0"), "beta is alpha's, not the agent's");
    }

    @Test
    void whatTheSbomCannotListFailsWithTheReason(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path pom = Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>agent</artifactId></project>");
        Path anonymous = jar(dir.resolve("lib/anonymous.jar"), null);
        IOException e = assertThrows(IOException.class, () -> MavenSbom.create("io.cresco:agent:1.3", pom, repo, List.of(anonymous)));
        assertTrue(e.getMessage().contains("anonymous.jar") && e.getMessage().contains("cannot list it"), e.getMessage());
        assertNotNull(MavenSbom.create("io.cresco:agent:1.3", pom, repo, List.of(dir.resolve("no-classes-dir"))), "a missing output directory is fine");
        e = assertThrows(IOException.class, () -> MavenSbom.create("io.cresco:agent:1.3", pom, repo, List.of(dir.resolve("gone.jar"))));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        e = assertThrows(IOException.class, () -> MavenSbom.create("${project.groupId}:agent", pom, repo, List.of()));
        assertTrue(e.getMessage().contains("group:artifact:version"), e.getMessage());
        e = assertThrows(IOException.class, () -> MavenSbom.create("io.cresco:agent:1.3", dir.resolve("${project.file}"), repo, List.of()));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void aPomWithADoctypeIsNotParsed(@TempDir Path dir) throws Exception {
        Path p = Files.writeString(dir.resolve("x.pom"), "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<project><artifactId>&x;</artifactId></project>");
        assertNull(MavenSbom.Pom.read(p), "no DTDs, so no external entities");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> byRef(List<Object> comps, String ref) {
        return (Map<String, Object>) comps.stream().filter(c -> ref.equals(((Map<String, Object>) c).get("bom-ref"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + ref + " in " + comps));
    }

    @SuppressWarnings("unchecked")
    static String license(Map<String, Object> c) {
        List<Object> l = (List<Object>) c.get("licenses");
        return l == null ? null : (String) ((Map<String, Object>) ((Map<String, Object>) l.get(0)).get("license")).get("name");
    }
}
