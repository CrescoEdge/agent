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
