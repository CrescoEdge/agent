package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 2 (review/api-20261104): inlined-library detection treats the groupId of EVERY pom.properties
 * a jar carries as the jar's "own" package prefix (EmbeddedSbom: ctx.ownGroup(p.getProperty("groupId"))), so classes of
 * other artifacts of that group are taken as accounted for and never listed. The real library.jar carries
 * META-INF/maven/io.siddhi/siddhi-core/pom.properties and inlines io.siddhi.query.* (siddhi-query-api,
 * siddhi-query-compiler) and io.siddhi.annotation (siddhi-annotations): none is in target/agent-*.cdx.json, which lists
 * only siddhi-core.
 */
class ReviewApi2SbomTest {

    @Test
    void siblingArtifactsOfAnInlinedGroupAreListed(@TempDir Path dir) throws Exception {
        Path bom = dir.resolve("agent.cdx.json");
        Files.writeString(bom, "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{\"component\":"
                + "{\"bom-ref\":\"pkg:maven/io.cresco/agent@1.3\",\"name\":\"agent\"}},\"components\":[],\"dependencies\":[]}");
        Path jars = Files.createDirectories(dir.resolve("classes"));
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Bundle-SymbolicName", "io.cresco.library");
        mf.getMainAttributes().putValue("Bundle-Version", "1.3.0");
        try (OutputStream fo = Files.newOutputStream(jars.resolve("library.jar")); JarOutputStream jo = new JarOutputStream(fo, mf)) {
            put(jo, "META-INF/maven/io.cresco/library/pom.properties", "groupId=io.cresco\nartifactId=library\nversion=1.3-SNAPSHOT\n");
            put(jo, "META-INF/maven/io.siddhi/siddhi-core/pom.properties", "groupId=io.siddhi\nartifactId=siddhi-core\nversion=5.1.33\n");
            for (String cls : new String[]{"io/cresco/library/plugin/PluginBuilder.class", "io/siddhi/core/SiddhiManager.class",
                    "io/siddhi/query/compiler/SiddhiCompiler.class", "io/siddhi/query/api/SiddhiApp.class",
                    "io/siddhi/annotation/Extension.class"}) {
                jo.putNextEntry(new JarEntry(cls));
                jo.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                jo.closeEntry();
            }
        }
        EmbeddedSbom.complete(bom, jars.toFile());
        String text = Files.readString(bom);
        List<String> missing = new ArrayList<>();
        for (String pkg : new String[]{"io.siddhi.query.compiler", "io.siddhi.query.api", "io.siddhi.annotation"}) {
            String artifactish = "siddhi-" + pkg.substring("io.siddhi.".length()).replace('.', '-');
            if (!text.contains(pkg) && !text.contains(pkg.replace('.', '/')) && !text.contains(artifactish)) missing.add(pkg);
        }
        assertEquals(List.of(), missing, "library.jar ships these classes, and the SBOM says nothing about them");
    }

    static void put(JarOutputStream jo, String name, String text) throws Exception {
        jo.putNextEntry(new JarEntry(name));
        jo.write(text.getBytes(StandardCharsets.UTF_8));
        jo.closeEntry();
    }
}
