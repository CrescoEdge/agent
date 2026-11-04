package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The known-vulnerability gate checks that the SBOM is this build's (every embedded jar, with its SHA-384, and nothing
 * else) and, when it passes, writes the marker the assembly puts into the agent jar.
 */
class GateMarkerTest {

    static String component(String file, String sha384) {
        return "{\"name\":\"" + file + "\",\"properties\":[{\"name\":\"cresco:embedded-file\",\"value\":\"" + file + "\"}],"
                + "\"hashes\":[{\"alg\":\"SHA-384\",\"content\":\"" + sha384 + "\"}]}";
    }

    @Test
    void theSbomMustBeThisBuildsAndAPassWritesTheMarker(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        byte[] a = "bundle a".getBytes(StandardCharsets.UTF_8), b = "bundle b".getBytes(StandardCharsets.UTF_8);
        Files.write(classes.resolve("a.jar"), a);
        Files.write(classes.resolve("b.jar"), b);
        Files.writeString(classes.resolve("log4j.properties"), "not a jar");
        String good = "{\"components\":[" + component("a.jar", KnownVulnerabilityGate.sha384(a)) + ","
                + component("b.jar", KnownVulnerabilityGate.sha384(b)) + "]}";
        Map<String, String> sums = new TreeMap<>();
        assertEquals(List.of(), KnownVulnerabilityGate.embeddedJars(good, classes, sums));
        assertEquals(List.of("a.jar", "b.jar"), new ArrayList<>(sums.keySet()));

        String stale = "{\"components\":[" + component("a.jar", KnownVulnerabilityGate.sha384("an older a".getBytes(StandardCharsets.UTF_8)))
                + "," + component("gone.jar", "00") + "]}";
        List<String> p = KnownVulnerabilityGate.embeddedJars(stale, classes, new TreeMap<>());
        assertTrue(p.contains("the SBOM's SHA-384 of a.jar is not the embedded file's (a stale SBOM?)"), p.toString());
        assertTrue(p.contains("the SBOM lists gone.jar, which this build does not embed (a stale SBOM?)"), p.toString());
        assertTrue(p.contains("b.jar is embedded but not in the SBOM"), p.toString());

        Path bom = Files.writeString(dir.resolve("agent.cdx.json"), good);
        Path out = dir.resolve("gate");
        KnownVulnerabilityGate.writeMarker(out, bom, sums, LocalDate.of(2026, 11, 4));
        assertEquals(good, Files.readString(out.resolve("META-INF/cresco/sbom.cdx.json")));
        String list = Files.readString(out.resolve("META-INF/cresco/embedded.sha384"));
        assertEquals(KnownVulnerabilityGate.sha384(a) + "  a.jar\n" + KnownVulnerabilityGate.sha384(b) + "  b.jar\n", list);
        String marker = Files.readString(out.resolve("marker.properties"));
        assertTrue(marker.contains("\ncresco.gate.marker=passed sha384:" + KnownVulnerabilityGate.sha384(good.getBytes(StandardCharsets.UTF_8)) + "\n"), marker);
        assertTrue(marker.contains("\ncresco.gate.files=sha384:" + KnownVulnerabilityGate.sha384(list.getBytes(StandardCharsets.UTF_8)) + "\n"), marker);
        assertTrue(marker.contains("\ncresco.gate.date=2026-11-04\n"), marker);
    }
}
