package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Finding libraries a bundle inlines as bare classes (no pom.properties, no nested jar). */
class InlinedLibrariesTest {

    /** Compiled with a ConstantValue attribute, as a library's version constant is. */
    static final class Versioned {
        public static final String VERSION = "4.13.2";
        public static final String OTHER = "not-a-version";
        public static final int NUMBER = 7;
    }

    static byte[] versionedClass() throws Exception {
        try (InputStream in = InlinedLibrariesTest.class.getResourceAsStream("InlinedLibrariesTest$Versioned.class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    @Test
    void packagesMatchTheLongestKnownRootOrAnUnidentifiedRoot() {
        assertEquals("io.dropwizard.metrics:metrics-jmx", InlinedLibraries.match("com/codahale/metrics/jmx").key());
        assertEquals("io.dropwizard.metrics:metrics-core", InlinedLibraries.match("com/codahale/metrics/health").key());
        assertEquals("org.antlr:antlr4-runtime", InlinedLibraries.match("org/antlr/v4/runtime/atn").key());
        assertEquals("org.antlr:antlr4", InlinedLibraries.match("org/antlr/v4/tool").key());
        assertEquals("com.google.guava:failureaccess", InlinedLibraries.match("com/google/common/util/concurrent/internal").key());
        assertEquals("com.google.guava:guava", InlinedLibraries.match("com/google/common/util/concurrent").key());
        InlinedLibraries.Match cm = InlinedLibraries.match("org/osgi/service/cm/annotations");
        assertEquals("org.osgi:org.osgi.service.cm", cm.key(), "an OSGi specification package names its own artifact");
        assertEquals("org/osgi/service/cm", cm.root);
        assertEquals("org/eclipse/jetty", InlinedLibraries.match("org/eclipse/jetty/server/handler").key(), "unidentified: by root");
        assertNull(InlinedLibraries.match("org/eclipse/jetty/server").known);
        assertEquals("zzz", InlinedLibraries.match("zzz/foo/bar").key(), "not a reversed domain: the first segment");
        assertFalse(InlinedLibraries.under("com/lmaxx", "com/lmax"));
        assertEquals("org/x", InlinedLibraries.classPackage("META-INF/versions/11/org/x/A.class"));
        assertNull(InlinedLibraries.classPackage("META-INF/versions/11/module-info.class"));
        assertNull(InlinedLibraries.classPackage("META-INF/x/A.class"));
        assertEquals("", InlinedLibraries.classPackage("Top.class"));
    }

    @Test
    void aVersionConstantIsReadFromTheClassFile() throws Exception {
        byte[] cls = versionedClass();
        assertEquals("4.13.2", InlinedLibraries.stringConstant(cls, "VERSION"));
        assertEquals("not-a-version", InlinedLibraries.stringConstant(cls, "OTHER"));
        assertNull(InlinedLibraries.stringConstant(cls, "NUMBER"), "not a String");
        assertNull(InlinedLibraries.stringConstant(cls, "MISSING"));
        assertNull(InlinedLibraries.stringConstant(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, "VERSION"));
        assertNull(InlinedLibraries.stringConstant(new byte[]{1, 2, 3}, "VERSION"));
        byte[] cut = java.util.Arrays.copyOf(cls, cls.length / 2);
        assertNull(InlinedLibraries.stringConstant(cut, "VERSION"), "a truncated class is no version, not an error");
    }

    @Test
    void versionsComeFromTheBundlesOwnRecordsInOrderAndDisagreementsAreKept() throws Exception {
        InlinedLibraries.Context ctx = new InlinedLibraries.Context();
        ctx.bundleVersion = "1.3.0.SNAPSHOT";
        InlinedLibraries.readHeaders(ctx,
                "com.codahale.metrics;version=\"4.2.39\";uses:=\"a.b,c.d\",com.lmax.disruptor;com.lmax.disruptor.dsl;version=\"3.4.2\","
                        + "com.google.common.base.internal;version=\"1.3.0.SNAPSHOT\"",
                "oshi-core-7.3.2.jar;g=\"com.github.oshi\";a=\"oshi-core\";v=\"7.3.2\",;g=\"org.atteo.classindex\";a=\"classindex\";v=\"3.13\"");
        InlinedLibraries.readDependencies(ctx, "From: 'x'\n  - Metrics Core (https://x) io.dropwizard.metrics:metrics-core:bundle:3.1.0\n"
                + "    License: Apache\n  - Fileupload commons-fileupload:commons-fileupload:jar:1.5\n");
        ctx.nestedCoordinates.put("com.github.oshi:oshi-core", "oshi-core-7.3.2.jar");
        Map<String, Integer> pkgs = new LinkedHashMap<>();
        pkgs.put("com/codahale/metrics", 100);
        pkgs.put("com/lmax/disruptor/dsl", 5);
        pkgs.put("org/apache/commons/fileupload", 50);
        pkgs.put("org/atteo/classindex", 20);
        pkgs.put("org/antlr/v4/runtime", 200);
        pkgs.put("com/google/common/base/internal", 1);
        pkgs.put("oshi/hardware", 30);
        byte[] versioned = versionedClass();
        Map<String, InlinedLibraries.Found> found = byKey(InlinedLibraries.find(pkgs, ctx,
                p -> p.equals("org/antlr/v4/runtime/RuntimeMetaData.class") ? versioned : null));

        InlinedLibraries.Found metrics = found.get("io.dropwizard.metrics:metrics-core");
        assertEquals("4.2.39", metrics.version);
        assertEquals("OSGi Export-Package", metrics.versionSource);
        assertEquals(List.of("3.1.0 (META-INF/DEPENDENCIES)"), metrics.otherVersions, "a disagreeing source is recorded, not hidden");
        assertEquals("3.4.2", found.get("com.lmax:disruptor").version, "several packages in one Export-Package clause");
        assertEquals("1.5", found.get("commons-fileupload:commons-fileupload").version);
        assertEquals("META-INF/DEPENDENCIES", found.get("commons-fileupload:commons-fileupload").versionSource);
        assertEquals("3.13", found.get("org.atteo.classindex:classindex").version, "an Embedded-Artifacts entry with no jar of its own");
        InlinedLibraries.Found antlr = found.get("org.antlr:antlr4-runtime");
        assertEquals("4.13.2", antlr.version);
        assertEquals("org.antlr.v4.runtime.RuntimeMetaData.VERSION", antlr.versionSource);
        assertEquals("unknown", found.get("com.google.guava:guava").version, "the bundle's own version is not the library's");
        InlinedLibraries.Found oshi = found.get("com.github.oshi:oshi-core");
        assertEquals("unknown", oshi.version, "the nested oshi-core jar's version says nothing about classes inlined beside it");
        assertEquals("oshi-core-7.3.2.jar", oshi.nestedCopy);
    }

    @Test
    void whatTheJarsOwnMetadataExplainsIsNotReportedAndUnidentifiedRootsCarryTheirDeclaredArtifacts() {
        InlinedLibraries.Context ctx = new InlinedLibraries.Context();
        ctx.ownGroup("io.cresco");
        ctx.ownGroup("io.siddhi");
        ctx.nestedPackages.add("com/google/common/base");
        ctx.listedCoordinates.add("org.slf4j:slf4j-api");
        InlinedLibraries.readHeaders(ctx, "org.eclipse.jetty.server;version=\"11.0.21\",org.eclipse.jetty.http;version=\"11.0.21\"", null);
        InlinedLibraries.readDependencies(ctx, "  - Jetty Server org.eclipse.jetty:jetty-server:jar:11.0.21\n"
                + "  - HTTP/2 org.eclipse.jetty.http2:http2-server:jar:11.0.21\n  - Other com.example:other:jar:1.0\n");
        Map<String, Integer> pkgs = new LinkedHashMap<>();
        pkgs.put("io/cresco/library", 10);
        pkgs.put("io/siddhi/query/api", 10);
        pkgs.put("com/google/common/base", 10);
        pkgs.put("org/slf4j", 10);
        pkgs.put("org/eclipse/jetty/server", 300);
        pkgs.put("org/eclipse/jetty/http", 100);
        pkgs.put("net/example/thing/core", 3);
        Map<String, InlinedLibraries.Found> found = byKey(InlinedLibraries.find(pkgs, ctx, p -> null));
        assertEquals(java.util.Set.of("org/eclipse/jetty", "net/example/thing"), found.keySet());
        InlinedLibraries.Found jetty = found.get("org/eclipse/jetty");
        assertEquals(400, jetty.classes());
        assertEquals("11.0.21", jetty.version);
        assertTrue(jetty.identified());
        assertEquals(List.of("org.eclipse.jetty:jetty-server:11.0.21", "org.eclipse.jetty.http2:http2-server:11.0.21"),
                jetty.declared.stream().map(d -> d[0] + ":" + d[1] + ":" + d[2]).toList());
        InlinedLibraries.Found thing = found.get("net/example/thing");
        assertFalse(thing.identified(), "nothing names it: listed by package, unidentified");
        assertEquals("unknown", thing.version);
    }

    static void jar(java.nio.file.Path file, Map<String, byte[]> entries, String bsn) throws Exception {
        java.nio.file.Files.createDirectories(file.getParent());
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        if (bsn != null) mf.getMainAttributes().putValue("Bundle-SymbolicName", bsn);
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(file), mf)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new java.util.jar.JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }

    static byte[] cls(String tag) {
        return ("\u00ca\u00fe" + tag).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> props(Map<String, Object> c) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Object o : (List<Object>) c.get("properties")) m.put(String.valueOf(((Map<String, Object>) o).get("name")), String.valueOf(((Map<String, Object>) o).get("value")));
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void inlinedClassesByteIdenticalToAReferenceArtifactAreThatArtifactAtThatVersion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path ref = dir.resolve("ref");
        jar(ref.resolve("org/example/lib/1.2.3/lib-1.2.3.jar"), Map.of("org/example/lib/A.class", cls("A"), "org/example/lib/B.class", cls("B"),
                "META-INF/versions/11/org/example/lib/A.class", cls("A11")), null);
        jar(ref.resolve("org/example/lib/1.2.4/lib-1.2.4.jar"), Map.of("org/example/lib/A.class", cls("A-new"), "org/example/lib/B.class", cls("B")), null);
        jar(ref.resolve("org/example/lib-common/1.2.3/lib-common-1.2.3.jar"), Map.of("org/example/lib/impl/C.class", cls("C")), null);
        InlinedLibraries.Reference r = InlinedLibraries.Reference.load(ref);
        assertEquals(3, r.artifacts.size());

        // a bundle that inlines lib 1.2.3 (multi-release copy included) and lib-common: two libraries, versions by content
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("io/cresco/thing/Main.class", cls("main"));
        entries.put("org/example/lib/A.class", cls("A"));
        entries.put("org/example/lib/B.class", cls("B"));
        entries.put("META-INF/versions/11/org/example/lib/A.class", cls("A11"));
        entries.put("org/example/lib/impl/C.class", cls("C"));
        java.nio.file.Path bundle = dir.resolve("thing.jar");
        jar(bundle, entries, "io.cresco.thing");
        Map<String, Map<String, Object>> libs = new LinkedHashMap<>();
        for (Object o : (List<Object>) EmbeddedSbom.component(bundle.toFile(), r).get("components")) {
            Map<String, Object> c = (Map<String, Object>) o;
            libs.put(c.get("group") + ":" + c.get("name"), c);
        }
        assertEquals(java.util.Set.of("org.example:lib", "org.example:lib-common"), libs.keySet());
        assertEquals("1.2.3", libs.get("org.example:lib").get("version"), "1.2.4's A differs: not 1.2.4");
        assertEquals("pkg:maven/org.example/lib@1.2.3", libs.get("org.example:lib").get("purl"));
        assertEquals("3", props(libs.get("org.example:lib")).get("cresco:inlined-classes"));
        assertTrue(props(libs.get("org.example:lib")).get("cresco:version-source").startsWith("content: every class byte-identical to org.example:lib:1.2.3 (lib-1.2.3.jar, SHA-256 "));
        assertEquals("1.2.3", libs.get("org.example:lib-common").get("version"));
        assertEquals("org.example.lib.impl", props(libs.get("org.example:lib-common")).get("cresco:inlined-packages"));

        // without the reference, or with one class that is not the artifact's, the version stays unknown (the gate fails)
        assertEquals("unknown", ((Map<String, Object>) ((List<Object>) EmbeddedSbom.component(bundle.toFile()).get("components")).get(0)).get("version"));
        entries.put("org/example/lib/B.class", cls("B-patched"));
        jar(bundle, entries, "io.cresco.thing");
        List<Object> patched = (List<Object>) EmbeddedSbom.component(bundle.toFile(), r).get("components");
        assertEquals(1, patched.size());
        assertEquals("unknown", ((Map<String, Object>) patched.get(0)).get("version"), "a patched class is not the artifact's");

        assertThrows(java.io.IOException.class, () -> {
            jar(dir.resolve("bad/x/1/y-1.jar"), Map.of(), null);
            InlinedLibraries.Reference.load(dir.resolve("bad"));
        }, "a reference jar must be in repository layout");
    }

    static Map<String, InlinedLibraries.Found> byKey(List<InlinedLibraries.Found> list) {
        Map<String, InlinedLibraries.Found> m = new LinkedHashMap<>();
        for (InlinedLibraries.Found f : list) m.put(f.match.key(), f);
        return m;
    }
}
