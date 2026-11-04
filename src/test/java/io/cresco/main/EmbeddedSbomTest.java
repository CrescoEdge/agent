package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The SBOM step adds every embedded jar, with hashes and the libraries inside it, to the CycloneDX BOM. */
class EmbeddedSbomTest {

    static final String BOM = "{\n  \"bomFormat\" : \"CycloneDX\",\n  \"specVersion\" : \"1.6\",\n"
            + "  \"metadata\" : { \"component\" : { \"bom-ref\" : \"pkg:maven/io.cresco/agent@1.3\", \"name\" : \"agent\" } },\n"
            + "  \"components\" : [ { \"bom-ref\" : \"pkg:maven/x/y@1\", \"name\" : \"y\", \"description\" : \"q\\\"uote \\u00e9\" } ],\n"
            + "  \"dependencies\" : [ { \"ref\" : \"pkg:maven/io.cresco/agent@1.3\", \"dependsOn\" : [ \"pkg:maven/x/y@1\" ] } ]\n}\n";

    static void copyResource(String name, Path dir) throws Exception {
        try (InputStream in = EmbeddedSbomTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddedJarsAreAddedWithCoordinatesHashesAndNestedLibraries(@TempDir Path dir) throws Exception {
        Path bom = dir.resolve("agent.cdx.json");
        Files.writeString(bom, BOM);
        Path jars = Files.createDirectories(dir.resolve("classes"));
        copyResource("org.osgi.util.function-1.2.0.jar", jars);
        copyResource("wsapi.jar", jars);
        assertEquals(2, EmbeddedSbom.complete(bom, jars.toFile()));
        assertEquals(2, EmbeddedSbom.complete(bom, jars.toFile()), "idempotent: a second run replaces, not duplicates");

        Map<String, Object> b = (Map<String, Object>) EmbeddedSbom.Json.parse(Files.readString(bom));
        List<Object> comps = (List<Object>) b.get("components");
        assertEquals(3, comps.size());
        Map<String, Object> pre = (Map<String, Object>) comps.get(0);
        assertEquals("q\"uote \u00e9", pre.get("description"), "existing content survives the rewrite");
        Map<String, Object> fn = (Map<String, Object>) comps.stream().filter(c -> "embedded:org.osgi.util.function-1.2.0.jar".equals(((Map<String, Object>) c).get("bom-ref"))).findFirst().orElseThrow();
        assertEquals("org.osgi", fn.get("group"));
        assertEquals("org.osgi.util.function", fn.get("name"));
        assertEquals("1.2.0", fn.get("version"));
        assertEquals("pkg:maven/org.osgi/org.osgi.util.function@1.2.0", fn.get("purl"));
        List<Object> hashes = (List<Object>) fn.get("hashes");
        assertEquals(List.of("SHA-256", "SHA-384", "SHA-512"), hashes.stream().map(h -> ((Map<String, Object>) h).get("alg")).toList());
        String sha384 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-384").digest(Files.readAllBytes(jars.resolve("org.osgi.util.function-1.2.0.jar"))));
        assertEquals(sha384, ((Map<String, Object>) hashes.get(1)).get("content"));

        Map<String, Object> ws = (Map<String, Object>) comps.stream().filter(c -> "embedded:wsapi.jar".equals(((Map<String, Object>) c).get("bom-ref"))).findFirst().orElseThrow();
        assertEquals("io.cresco", ws.get("group"));
        List<Object> nested = (List<Object>) ws.get("components");
        assertTrue(nested.stream().anyMatch(n -> "io.netty".equals(((Map<String, Object>) n).get("group"))), "wsapi's embedded Netty is listed");

        Map<String, Object> rootDeps = (Map<String, Object>) ((List<Object>) b.get("dependencies")).get(0);
        assertEquals(List.of("pkg:maven/x/y@1", "embedded:org.osgi.util.function-1.2.0.jar", "embedded:wsapi.jar"), rootDeps.get("dependsOn"));
    }

    static byte[] jarBytes(Map<String, byte[]> entries, String... manifestAttrs) throws Exception {
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        for (int i = 0; i < manifestAttrs.length; i += 2) mf.getMainAttributes().putValue(manifestAttrs[i], manifestAttrs[i + 1]);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.jar.JarOutputStream jo = new java.util.jar.JarOutputStream(bos, mf)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jo.putNextEntry(new java.util.jar.JarEntry(e.getKey()));
                jo.write(e.getValue());
                jo.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBundlesInlinedLibrariesAreListedWithVersionsAndDeclaredArtifacts(@TempDir Path dir) throws Exception {
        byte[] cls = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        Map<String, byte[]> nested = new java.util.LinkedHashMap<>();
        nested.put("com/google/common/base/Strings.class", cls);
        nested.put("META-INF/maven/com.google.guava/guava/pom.properties", "groupId=com.google.guava\nartifactId=guava\nversion=33.6.0-jre\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("META-INF/maven/org.example/web/pom.properties", "groupId=org.example\nartifactId=web\nversion=5.1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/DEPENDENCIES", ("  - Jetty org.eclipse.jetty:jetty-server:jar:11.0.21\n"
                + "  - IO commons-io:commons-io:jar:2.11.0\n").getBytes(StandardCharsets.UTF_8));
        entries.put("guava-33.6.0-jre.jar", jarBytes(nested));
        entries.put("org/example/web/Main.class", cls);
        entries.put("com/google/common/base/Strings.class", cls);                  // a copy of the nested jar's package
        entries.put("org/eclipse/jetty/server/Server.class", cls);
        entries.put("org/apache/commons/io/IOUtils.class", cls);
        entries.put("com/lmax/disruptor/RingBuffer.class", cls);
        entries.put("net/example/mystery/Thing.class", cls);
        Path jars = Files.createDirectories(dir.resolve("classes"));
        Files.write(jars.resolve("web.jar"), jarBytes(entries, "Bundle-SymbolicName", "org.example.web", "Bundle-Version", "5.1.0",
                "Export-Package", "org.eclipse.jetty.server;version=\"11.0.21\",com.lmax.disruptor;version=\"3.4.2\"",
                "Embedded-Artifacts", "guava-33.6.0-jre.jar;g=\"com.google.guava\";a=\"guava\";v=\"33.6.0-jre\""));
        Path bom = dir.resolve("agent.cdx.json");
        Files.writeString(bom, BOM);
        EmbeddedSbom.complete(bom, jars.toFile());
        Map<String, Object> b = (Map<String, Object>) EmbeddedSbom.Json.parse(Files.readString(bom));
        Map<String, Object> web = (Map<String, Object>) ((List<Object>) b.get("components")).stream()
                .filter(c -> "embedded:web.jar".equals(((Map<String, Object>) c).get("bom-ref"))).findFirst().orElseThrow();
        Map<String, Map<String, Object>> sub = new java.util.LinkedHashMap<>();
        for (Object o : (List<Object>) web.get("components")) sub.put((String) ((Map<String, Object>) o).get("bom-ref"), (Map<String, Object>) o);
        assertEquals(java.util.Set.of("embedded:web.jar!guava-33.6.0-jre.jar", "embedded:web.jar!inlined:org/eclipse/jetty",
                "embedded:web.jar!inlined:commons-io:commons-io", "embedded:web.jar!inlined:com.lmax:disruptor",
                "embedded:web.jar!inlined:net/example/mystery"), sub.keySet(),
                "own classes and the nested jar's packages are not inlined libraries");
        Map<String, Object> io = sub.get("embedded:web.jar!inlined:commons-io:commons-io");
        assertEquals("pkg:maven/commons-io/commons-io@2.11.0", io.get("purl"));
        assertEquals("pkg:maven/com.lmax/disruptor@3.4.2", sub.get("embedded:web.jar!inlined:com.lmax:disruptor").get("purl"));
        Map<String, Object> jetty = sub.get("embedded:web.jar!inlined:org/eclipse/jetty");
        assertEquals("11.0.21", jetty.get("version"));
        assertEquals("pkg:maven/org.eclipse.jetty/jetty-server@11.0.21",
                ((Map<String, Object>) ((List<Object>) jetty.get("components")).get(0)).get("purl"), "the artifact the bundle declares");
        Map<String, Object> mystery = sub.get("embedded:web.jar!inlined:net/example/mystery");
        assertNull(mystery.get("purl"));
        String props = EmbeddedSbom.Json.write(mystery.get("properties"));
        assertTrue(props.contains("\"cresco:identified\"") && props.contains("\"false\"") && props.contains("net.example.mystery"), props);
    }

    @Test
    void notABomIsRefused(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("x.json");
        Files.writeString(bad, "{\"hello\": 1}");
        assertThrows(java.io.IOException.class, () -> EmbeddedSbom.complete(bad, dir.toFile()));
        Files.writeString(bad, "{\"bomFormat\": \"CycloneDX\",");
        assertThrows(Exception.class, () -> EmbeddedSbom.complete(bad, dir.toFile()));
    }

    @Test
    void jsonRoundTrips() throws Exception {
        String s = "{\"a\":[1,2.5,true,false,null,\"x\\ny\\t\\\\\\\"\"],\"b\":{},\"c\":[]}";
        Object v = EmbeddedSbom.Json.parse(s);
        assertEquals(v, EmbeddedSbom.Json.parse(EmbeddedSbom.Json.write(v)));
        assertEquals(new String("\u0001".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                ((List<?>) ((Map<?, ?>) EmbeddedSbom.Json.parse(EmbeddedSbom.Json.write(Map.of("k", List.of("\u0001"))))).get("k")).get(0));
    }
}
