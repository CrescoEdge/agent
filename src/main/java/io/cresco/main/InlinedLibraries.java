package io.cresco.main;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Libraries a bundle carries as bare classes, inlined without a {@code pom.properties}. An SBOM built from Maven metadata
 * never sees them: library.jar inlines LMAX Disruptor, Dropwizard Metrics, ANTLR 4 and more this way. {@link #find}
 * takes the packages of a jar's classes, sets aside those the jar's own metadata accounts for (its own group and
 * Bundle-SymbolicName, its other pom.properties, its nested jars) and names the rest: from {@link #KNOWN}, a table of
 * libraries by package root, else by the package root itself, marked unidentified. Versions come from the bundle's
 * {@code Embedded-Artifacts} or {@code Export-Package} headers, or from a version constant the library compiles in.
 */
final class InlinedLibraries {

    private InlinedLibraries() {}

    /** A library known by its package roots (slash form), and optionally a class whose String constant is its version. */
    static final class Known {
        final String group, artifact;
        final List<String> roots;
        String versionClass, versionField;

        Known(String group, String artifact, String... roots) {
            this.group = group;
            this.artifact = artifact;
            this.roots = List.of(roots);
        }

        Known version(String classPath, String field) {
            this.versionClass = classPath;
            this.versionField = field;
            return this;
        }
    }

    /** Where one package belongs: a known library (its coordinates) or an unidentified package root. */
    static final class Match {
        final Known known;          // null: unidentified
        final String group, artifact, root;

        Match(Known known, String group, String artifact, String root) {
            this.known = known;
            this.group = group;
            this.artifact = artifact;
            this.root = root;
        }

        String key() { return known != null ? group + ":" + artifact : root; }
    }

    /**
     * Libraries known to be inlined into bundles, by package root; the longest matching root wins. A root ending in
     * {@code /*} names one artifact per package under it ({@code *} in the artifact is that package's next segment). Only
     * roots that belong to one Maven artifact: packages split across several artifacts (Netty, Jetty, ActiveMQ, Bouncy
     * Castle ...) are listed by their root, with the artifacts the bundle declares under it, rather than a guessed one.
     */
    static final List<Known> KNOWN = List.of(
            new Known("com.lmax", "disruptor", "com/lmax/disruptor"),
            new Known("io.dropwizard.metrics", "metrics-core", "com/codahale/metrics"),
            new Known("io.dropwizard.metrics", "metrics-jmx", "com/codahale/metrics/jmx"),
            new Known("org.antlr", "antlr4-runtime", "org/antlr/v4/runtime").version("org/antlr/v4/runtime/RuntimeMetaData.class", "VERSION"),
            new Known("org.antlr", "antlr4", "org/antlr/v4"),
            new Known("org.antlr", "antlr-runtime", "org/antlr/runtime"),
            new Known("org.atteo.classindex", "classindex", "org/atteo/classindex"),
            new Known("org.latencyutils", "LatencyUtils", "org/LatencyUtils"),
            new Known("org.hdrhistogram", "HdrHistogram", "org/HdrHistogram"),
            new Known("com.google.guava", "guava", "com/google/common", "com/google/thirdparty"),
            new Known("com.google.guava", "failureaccess", "com/google/common/util/concurrent/internal"),
            new Known("com.google.code.gson", "gson", "com/google/gson"),
            new Known("io.micrometer", "micrometer-core", "io/micrometer/core"),
            new Known("io.micrometer", "micrometer-commons", "io/micrometer/common"),
            new Known("io.micrometer", "micrometer-observation", "io/micrometer/observation"),
            new Known("io.micrometer", "micrometer-registry-jmx", "io/micrometer/jmx"),
            new Known("org.quartz-scheduler", "quartz", "org/quartz", "org/terracotta/quartz"),
            new Known("com.fasterxml.jackson.core", "jackson-core", "com/fasterxml/jackson/core"),
            new Known("com.fasterxml.jackson.core", "jackson-databind", "com/fasterxml/jackson/databind"),
            new Known("com.fasterxml.jackson.core", "jackson-annotations", "com/fasterxml/jackson/annotation"),
            new Known("org.slf4j", "slf4j-api", "org/slf4j"),
            new Known("commons-io", "commons-io", "org/apache/commons/io"),
            new Known("org.apache.commons", "commons-lang3", "org/apache/commons/lang3"),
            new Known("org.apache.commons", "commons-text", "org/apache/commons/text"),
            new Known("org.apache.commons", "commons-collections4", "org/apache/commons/collections4"),
            new Known("commons-collections", "commons-collections", "org/apache/commons/collections"),
            new Known("commons-codec", "commons-codec", "org/apache/commons/codec"),
            new Known("commons-fileupload", "commons-fileupload", "org/apache/commons/fileupload"),
            new Known("commons-logging", "commons-logging", "org/apache/commons/logging"),
            new Known("org.apache.commons", "commons-compress", "org/apache/commons/compress"),
            new Known("org.json", "json", "org/json"),
            new Known("net.minidev", "json-smart", "net/minidev/json"),
            new Known("net.minidev", "accessors-smart", "net/minidev/asm"),
            new Known("com.jayway.jsonpath", "json-path", "com/jayway/jsonpath"),
            new Known("org.ow2.asm", "asm", "org/objectweb/asm"),
            new Known("org.ow2.asm", "asm-commons", "org/objectweb/asm/commons"),
            new Known("org.ow2.asm", "asm-tree", "org/objectweb/asm/tree"),
            new Known("org.yaml", "snakeyaml", "org/yaml/snakeyaml"),
            new Known("c3p0", "c3p0", "com/mchange/v2/c3p0"),
            new Known("com.mchange", "mchange-commons-java", "com/mchange"),
            new Known("jakarta.jms", "jakarta.jms-api", "jakarta/jms"),
            new Known("javax.jms", "javax.jms-api", "javax/jms"),
            new Known("org.apache.tapestry", "tapestry-json", "org/apache/tapestry5/json"),
            new Known("org.owasp.encoder", "encoder", "org/owasp/encoder"),
            new Known("org.mvel", "mvel2", "org/mvel2"),
            new Known("com.github.oshi", "oshi-core", "oshi"),
            new Known("io.siddhi", "siddhi-core", "io/siddhi/core"),
            new Known("io.siddhi", "siddhi-query-api", "io/siddhi/query/api"),
            new Known("io.siddhi", "siddhi-query-compiler", "io/siddhi/query/compiler"),
            new Known("io.siddhi", "siddhi-annotations", "io/siddhi/annotation"),
            new Known("javax.servlet", "javax.servlet-api", "javax/servlet"),
            new Known("jakarta.servlet", "jakarta.servlet-api", "jakarta/servlet"),
            new Known("net.sf.kxml", "kxml2", "org/kxml2"),
            new Known("xmlpull", "xmlpull", "org/xmlpull/v1"),
            // OSGi specification packages: one artifact per package root, org.osgi:org.osgi.service.cm and so on
            new Known("org.osgi", "org.osgi.service.*", "org/osgi/service/*"),
            new Known("org.osgi", "org.osgi.util.*", "org/osgi/util/*"));

    /** A library found inlined in a jar. */
    static final class Found {
        final Match match;
        final String root;                                       // the package root, slash form
        final SortedMap<String, Integer> packages = new TreeMap<>();   // package (dots) to its class count
        String version = "unknown";
        String versionSource = "unknown";
        final List<String> otherVersions = new ArrayList<>();    // "3.1.0 (META-INF/DEPENDENCIES)": sources that disagree
        final List<String[]> declared = new ArrayList<>();       // {group, artifact, version, source} the bundle declares under the root
        String nestedCopy;                                       // a nested jar of the same artifact (these classes are not in it)

        Found(Match match) {
            this.match = match;
            this.root = match.root;
        }

        boolean identified() { return match.known != null || !declared.isEmpty(); }

        int classes() {
            int n = 0;
            for (int c : packages.values()) n += c;
            return n;
        }

        String rootName() { return root.replace('/', '.'); }
    }

    /** What a jar's own metadata accounts for, and where versions can be read. */
    static final class Context {
        final Set<String> ownPrefixes = new HashSet<>();         // slash form: its group, Bundle-SymbolicName, other pom groups
        final Set<String> nestedPackages = new HashSet<>();      // slash form: packages its nested jars contain
        final Set<String> listedCoordinates = new HashSet<>();   // group:artifact its pom.properties already list
        final Map<String, String> nestedCoordinates = new HashMap<>();   // group:artifact of a nested jar to its path
        final Map<String, String> embeddedFiles = new HashMap<>();       // group:artifact to its Embedded-Artifacts file
        final Map<String, String> exportVersions = new HashMap<>();      // package (dots) to Export-Package version
        final Map<String, String> embeddedArtifacts = new LinkedHashMap<>();   // group:artifact to Embedded-Artifacts version
        final Map<String, String> dependencies = new LinkedHashMap<>();        // group:artifact to META-INF/DEPENDENCIES version
        String bundleVersion;

        /** The {group, artifact, version} the bundle's Embedded-Artifacts header gives an embedded file, or null. */
        String[] embeddedFileCoordinates(String file) {
            for (Map.Entry<String, String> e : embeddedFiles.entrySet()) {
                if (file.equals(e.getValue()) || e.getValue().endsWith("/" + file)) {
                    String ga = e.getKey();
                    int colon = ga.indexOf(':');
                    String v = embeddedArtifacts.get(ga);
                    if (colon > 0 && v != null) return new String[]{ga.substring(0, colon), ga.substring(colon + 1), v};
                }
            }
            return null;
        }

        void ownGroup(String dotted) {
            if (dotted != null && !dotted.isBlank()) ownPrefixes.add(dotted.trim().replace('.', '/'));
        }

        /**
         * The packages an inlined artifact's own pom.properties accounts for: its known roots ({@link #KNOWN}), else
         * its group plus its artifact id less the group's last segment ({@code io.siddhi:siddhi-core} is
         * {@code io/siddhi/core}). Never the whole group: sibling artifacts of that group stay to be listed.
         */
        void artifactRoot(String group, String artifact) {
            if (group == null || artifact == null || group.isBlank() || artifact.isBlank()) return;
            boolean known = false;
            for (Known k : KNOWN) {
                if (k.group.equals(group) && k.artifact.equals(artifact)) {
                    for (String r : k.roots) if (!r.endsWith("/*")) ownPrefixes.add(r);
                    known = true;
                }
            }
            if (known) return;
            String g = group.trim(), last = g.substring(g.lastIndexOf('.') + 1);
            String a = artifact.trim();
            if (a.startsWith(last + "-")) a = a.substring(last.length() + 1);
            ownPrefixes.add(g.replace('.', '/') + "/" + a.replace('-', '/').replace('.', '/'));
        }
    }

    /**
     * The libraries inlined in a jar: {@code classPackages} maps each package (slash form) to how many classes the jar
     * has in it; {@code classBytes} reads one of the jar's classes (for version constants), or returns null.
     */
    static List<Found> find(Map<String, Integer> classPackages, Context ctx, Function<String, byte[]> classBytes) {
        Map<String, Found> found = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(classPackages).entrySet()) {
            String pkg = e.getKey();
            if (pkg.isEmpty() || ctx.nestedPackages.contains(pkg) || underAny(pkg, ctx.ownPrefixes)) continue;
            Match m = match(pkg);
            if (m.known != null && ctx.listedCoordinates.contains(m.key())) continue;
            Found f = found.computeIfAbsent(m.key(), x -> new Found(m));
            f.packages.put(pkg.replace('/', '.'), e.getValue());
        }
        for (Found f : found.values()) {
            if (f.match.known != null) f.nestedCopy = ctx.nestedCoordinates.get(f.match.key());
            version(f, ctx, classBytes);
            if (f.match.known == null) declaredUnder(f, ctx);
        }
        return new ArrayList<>(found.values());
    }

    /** The artifacts the bundle declares (Embedded-Artifacts, META-INF/DEPENDENCIES) whose group lies under the root. */
    private static void declaredUnder(Found f, Context ctx) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ctx.dependencies.entrySet()) add(out, f, ctx, e, "META-INF/DEPENDENCIES");
        for (Map.Entry<String, String> e : ctx.embeddedArtifacts.entrySet()) add(out, f, ctx, e, "OSGi Embedded-Artifacts");
        f.declared.addAll(out.values());
    }

    private static void add(Map<String, String[]> out, Found f, Context ctx, Map.Entry<String, String> e, String source) {
        String ga = e.getKey();
        int colon = ga.indexOf(':');
        String g = ga.substring(0, colon), a = ga.substring(colon + 1);
        if (ctx.listedCoordinates.contains(ga) || ctx.nestedCoordinates.containsKey(ga) || !under(g.replace('.', '/'), f.root)) return;
        out.put(ga, new String[]{g, a, e.getValue(), source});
    }

    private static void version(Found f, Context ctx, Function<String, byte[]> classBytes) {
        Map<String, String> candidates = new LinkedHashMap<>();     // source to version, in order of trust
        String ga = f.match.known != null ? f.match.group + ":" + f.match.artifact : null;
        // an Embedded-Artifacts entry for a nested jar describes that jar, not classes inlined beside it
        if (ga != null && ctx.embeddedArtifacts.containsKey(ga) && !ctx.nestedCoordinates.containsKey(ga)
                && !ctx.nestedCoordinates.containsValue(ctx.embeddedFiles.get(ga))) {
            candidates.put("OSGi Embedded-Artifacts", ctx.embeddedArtifacts.get(ga));
        }
        Map<String, Integer> votes = new HashMap<>();
        for (String pkg : f.packages.keySet()) {
            String v = ctx.exportVersions.get(pkg);
            if (v == null || v.isBlank() || v.equals("0.0.0") || v.equals(ctx.bundleVersion)) continue;
            votes.merge(v, 1, Integer::sum);
        }
        String best = null;
        for (Map.Entry<String, Integer> v : votes.entrySet()) {
            if (best == null || v.getValue() > votes.get(best)) best = v.getKey();
        }
        if (best != null) candidates.put("OSGi Export-Package", best);
        if (ga != null && ctx.dependencies.containsKey(ga)) candidates.put("META-INF/DEPENDENCIES", ctx.dependencies.get(ga));
        Known k = f.match.known;
        if (k != null && k.versionClass != null) {
            byte[] cls = classBytes.apply(k.versionClass);
            String v = cls == null ? null : stringConstant(cls, k.versionField);
            if (v != null && v.matches("[0-9][A-Za-z0-9._+-]{0,63}")) {
                candidates.put(k.versionClass.replace('/', '.').replaceAll("\\.class$", "") + "." + k.versionField, v);
            }
        }
        for (Map.Entry<String, String> c : candidates.entrySet()) {
            if ("unknown".equals(f.versionSource)) {
                f.version = c.getValue();
                f.versionSource = c.getKey();
            } else if (!c.getValue().equals(f.version)) {
                f.otherVersions.add(c.getValue() + " (" + c.getKey() + ")");
            }
        }
    }

    /** Where a package (slash form) belongs: the longest known root, else an unidentified root. */
    static Match match(String pkg) {
        Known best = null;
        String bestRoot = null;
        for (Known k : KNOWN) {
            for (String r : k.roots) {
                String concrete = r;
                if (r.endsWith("/*")) {
                    String prefix = r.substring(0, r.length() - 2);
                    if (!pkg.startsWith(prefix + "/")) continue;
                    String next = pkg.substring(prefix.length() + 1).split("/")[0];
                    concrete = prefix + "/" + next;
                }
                if (under(pkg, concrete) && (bestRoot == null || concrete.length() > bestRoot.length())) {
                    best = k;
                    bestRoot = concrete;
                }
            }
        }
        if (best == null) return new Match(null, null, null, genericRoot(pkg));
        String artifact = best.artifact;
        if (artifact.contains("*")) artifact = artifact.replace("*", bestRoot.substring(bestRoot.lastIndexOf('/') + 1));
        return new Match(best, best.group, artifact, bestRoot);
    }

    /** First package segments that are reversed domain names: a root under them takes three segments, else one. */
    private static final Set<String> DOMAINS = Set.of("com", "org", "net", "io", "edu", "gov", "javax", "jakarta", "sun",
            "jdk", "de", "uk", "ch", "fr", "nl", "it", "eu", "us", "info", "biz", "me", "dev", "app", "co", "ai");

    /** An unidentified library's root: org/example/lib for reversed-domain packages, else the first segment (oshi). */
    static String genericRoot(String pkg) {
        String[] s = pkg.split("/");
        int n = DOMAINS.contains(s[0]) ? 3 : 1;
        return String.join("/", java.util.Arrays.copyOf(s, Math.min(n, s.length)));
    }

    static boolean under(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + "/");
    }

    private static boolean underAny(String pkg, Collection<String> roots) {
        for (String r : roots) if (under(pkg, r)) return true;
        return false;
    }

    /** The package (slash form) of a jar entry that is a class, or null; multi-release entries count as their base. */
    static String classPackage(String entry) {
        if (!entry.endsWith(".class")) return null;
        String n = entry;
        if (n.startsWith("META-INF/versions/")) {
            int slash = n.indexOf('/', "META-INF/versions/".length());
            if (slash < 0) return null;
            n = n.substring(slash + 1);
        } else if (n.startsWith("META-INF/")) {
            return null;
        }
        if (n.endsWith("module-info.class")) return null;
        int last = n.lastIndexOf('/');
        return last < 0 ? "" : n.substring(0, last);
    }

    /**
     * The clauses of an OSGi header ({@code a;b;version="1";uses:="x,y", c}): for each clause, its names (the parts
     * without '=') under the key "" (comma-joined) and its attributes and directives by name, unquoted.
     */
    static List<Map<String, String>> clauses(String header) {
        List<Map<String, String>> out = new ArrayList<>();
        if (header == null) return out;
        for (String clause : split(header, ',')) {
            Map<String, String> m = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (String part : split(clause, ';')) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq < 0) {
                    if (!p.isEmpty()) names.add(p);
                    continue;
                }
                String k = p.substring(0, eq).trim();
                if (k.endsWith(":")) k = k.substring(0, k.length() - 1);
                String v = p.substring(eq + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                m.put(k, v);
            }
            m.put("", String.join(",", names));
            out.add(m);
        }
        return out;
    }

    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Fill the context's declared artifacts from a bundle's META-INF/DEPENDENCIES (lines "... group:artifact:type:version"). */
    static void readDependencies(Context ctx, String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)\\s([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):(?:jar|bundle):([A-Za-z0-9_.+-]+)\\s*$").matcher(text);
        while (m.find()) ctx.dependencies.putIfAbsent(m.group(1) + ":" + m.group(2), m.group(3));
    }

    /** Fill the context's version sources from a bundle manifest's Export-Package and Embedded-Artifacts headers. */
    static void readHeaders(Context ctx, String exportPackage, String embeddedArtifacts) {
        for (Map<String, String> c : clauses(exportPackage)) {
            String v = c.get("version");
            if (v == null) continue;
            for (String pkg : c.get("").split(",")) if (!pkg.isEmpty()) ctx.exportVersions.put(pkg, v);
        }
        for (Map<String, String> c : clauses(embeddedArtifacts)) {
            String g = c.get("g"), a = c.get("a"), v = c.get("v");
            if (g == null || a == null || v == null) continue;
            ctx.embeddedArtifacts.put(g + ":" + a, v);
            if (!c.get("").isEmpty()) ctx.embeddedFiles.put(g + ":" + a, c.get(""));
        }
    }

    /** The value of a {@code static final String} field's ConstantValue in a class file, or null. */
    static String stringConstant(byte[] cls, String field) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls))) {
            if (in.readInt() != 0xCAFEBABE) return null;
            in.readUnsignedShort();
            in.readUnsignedShort();
            int n = in.readUnsignedShort();
            Object[] pool = new Object[n];
            int[] tag = new int[n];
            for (int i = 1; i < n; i++) {
                int t = in.readUnsignedByte();
                tag[i] = t;
                switch (t) {
                    case 1: pool[i] = in.readUTF(); break;                          // Utf8 (modified UTF-8, as readUTF reads it)
                    case 3: case 4: in.readInt(); break;                            // Integer, Float
                    case 5: case 6: in.readLong(); i++; break;                      // Long, Double: two slots
                    case 7: case 8: case 16: case 19: case 20: pool[i] = in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;  // MethodHandle
                    default: return null;
                }
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int interfaces = in.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
            int fields = in.readUnsignedShort();
            for (int f = 0; f < fields; f++) {
                in.readUnsignedShort();
                int name = in.readUnsignedShort();
                in.readUnsignedShort();
                int attrs = in.readUnsignedShort();
                for (int a = 0; a < attrs; a++) {
                    int attrName = in.readUnsignedShort();
                    int len = in.readInt();
                    if (len == 2 && field.equals(pool[name]) && "ConstantValue".equals(pool[attrName])) {
                        int cv = in.readUnsignedShort();
                        return cv > 0 && cv < n && tag[cv] == 8 && pool[cv] instanceof Integer i2 && i2 > 0 && i2 < n
                                && pool[i2] instanceof String s ? s : null;
                    }
                    if (len < 0 || len > cls.length) return null;
                    in.readFully(new byte[len]);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
