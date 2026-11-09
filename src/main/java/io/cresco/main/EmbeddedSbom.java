package io.cresco.main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Writes the agent's CycloneDX SBOM with what the agent jar really ships. {@link MavenSbom} lists the agent and its Maven
 * dependencies, but most of the agent is the OSGi bundles embedded as resources (controller, library, wsapi, the Felix
 * bundles ...), and each of those carries further libraries. This adds:
 * <ul>
 *   <li>one component per embedded jar ({@code src/main/resources/*.jar}): its Maven coordinates and purl when the
 *       jar names them ({@code META-INF/maven/.../pom.properties}), else its Bundle-SymbolicName and Bundle-Version;
 *       SHA-256, SHA-384 and SHA-512 of the jar; the embedded file name as a property;</li>
 *   <li>under each, the libraries it carries: nested jars (with their own coordinates and SHA-256), dependencies named
 *       by further {@code pom.properties}, and libraries inlined as bare classes without either, found by package
 *       ({@link InlinedLibraries});</li>
 *   <li>the dependency edges from the agent to each embedded jar.</li>
 * </ul>
 * Runs in the build (package phase, see pom.xml) with no Maven plugin beyond maven-antrun-plugin, so an offline build
 * writes the SBOM like any other; when it cannot, the build fails with the reason. Usage:
 * <pre>
 *   EmbeddedSbom --create &lt;bom.json&gt; &lt;dir-with-embedded-jars&gt; &lt;group:artifact:version&gt; &lt;pom.xml&gt;
 *                &lt;local repository&gt; &lt;runtime classpath&gt; [&lt;reference jars&gt;]      write a new BOM
 *   EmbeddedSbom &lt;bom.json&gt; &lt;dir-with-embedded-jars&gt;               add the embedded jars to an existing BOM
 * </pre>
 * The reference jars (a directory in Maven repository layout, which the build fills with maven-dependency-plugin) name
 * the libraries a bundle inlines without metadata by content ({@link InlinedLibraries.Reference}); given, the directory
 * must exist.
 */
public final class EmbeddedSbom {

    private EmbeddedSbom() {}

    public static void main(String[] args) {
        Path bom = null;
        try {
            if ((args.length == 7 || args.length == 8) && "--create".equals(args[0])) {
                bom = Paths.get(args[1]);
                InlinedLibraries.Reference ref = InlinedLibraries.Reference.NONE;
                if (args.length == 8) {
                    if (!Files.isDirectory(Paths.get(args[7]))) throw new IOException("the reference jars directory " + args[7] + " does not exist");
                    ref = InlinedLibraries.Reference.load(Paths.get(args[7]));
                }
                List<Path> classpath = new ArrayList<>();
                for (String e : args[6].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!e.isBlank()) classpath.add(Paths.get(e));
                }
                Map<String, Object> created = MavenSbom.create(args[3], Paths.get(args[4]), Paths.get(args[5]), classpath);
                int maven = ((List<?>) created.get("components")).size();
                Files.createDirectories(bom.toAbsolutePath().getParent());
                write(bom, created);
                int embedded = complete(bom, new File(args[2]), ref);
                System.out.println("EmbeddedSbom: wrote " + bom + " (CycloneDX 1.6): " + maven + " Maven dependencies, "
                        + embedded + " embedded jars" + summary(bom));
            } else if (args.length == 2) {
                bom = Paths.get(args[0]);
                int n = complete(bom, new File(args[1]));
                System.out.println("EmbeddedSbom: added " + n + " embedded jar(s) to " + bom + summary(bom));
            } else {
                System.err.println("usage: EmbeddedSbom --create <bom.json> <dir-with-embedded-jars> <group:artifact:version> "
                        + "<pom.xml> <local repository> <runtime classpath> [<reference jars>]\n"
                        + "       EmbeddedSbom <bom.json> <dir-with-embedded-jars>");
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("EmbeddedSbom: FAILED to write the SBOM " + (bom != null ? bom : "") + ": " + e.getMessage());
            System.err.println("EmbeddedSbom: the agent is not built without its SBOM; fix the cause above and build again.");
            System.exit(1);
        }
    }

    /** ", N nested libraries, M inlined libraries (K unidentified: ...)" for the build log; warns about unidentified ones. */
    @SuppressWarnings("unchecked")
    private static String summary(Path bomFile) throws IOException {
        Map<String, Object> bom = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(bomFile), StandardCharsets.UTF_8));
        int nested = 0, inlined = 0, byContent = 0;
        List<String> unidentified = new ArrayList<>();
        for (Object o : (List<Object>) bom.getOrDefault("components", List.of())) {
            Object sub = ((Map<String, Object>) o).get("components");
            if (!(sub instanceof List)) continue;
            for (Object n : (List<Object>) sub) {
                Map<String, Object> c = (Map<String, Object>) n;
                String root = property(c, "cresco:inlined-package-root");
                if (root == null) {
                    nested++;
                    continue;
                }
                inlined++;
                String vs = property(c, "cresco:version-source");
                if (vs != null && vs.startsWith("content:")) byContent++;
                if ("false".equals(property(c, "cresco:identified"))) {
                    unidentified.add(root + " in " + property(c, "cresco:embedded-file"));
                }
            }
        }
        for (String u : unidentified) {
            System.out.println("EmbeddedSbom: WARNING inlined classes of an unidentified library: " + u
                    + " (listed by package; name it in io.cresco.main.InlinedLibraries.KNOWN)");
        }
        return ", " + nested + " nested libraries, " + inlined + " inlined libraries (" + byContent + " versioned by content, "
                + unidentified.size() + " unidentified)";
    }

    @SuppressWarnings("unchecked")
    private static String property(Map<String, Object> c, String name) {
        Object props = c.get("properties");
        if (!(props instanceof List)) return null;
        for (Object o : (List<Object>) props) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (name.equals(p.get("name"))) return String.valueOf(p.get("value"));
        }
        return null;
    }

    private static void write(Path bomFile, Map<String, Object> bom) throws IOException {
        Path tmp = bomFile.resolveSibling(bomFile.getFileName() + ".tmp");
        Files.write(tmp, Json.write(bom).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, bomFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Add the embedded jars in {@code dir} to the BOM file; returns how many were added. */
    static int complete(Path bomFile, File dir) throws IOException {
        return complete(bomFile, dir, InlinedLibraries.Reference.NONE);
    }

    /** As {@link #complete(Path, File)}, naming inlined libraries by content against {@code ref}. */
    @SuppressWarnings("unchecked")
    static int complete(Path bomFile, File dir, InlinedLibraries.Reference ref) throws IOException {
        Object parsed = Json.parse(new String(Files.readAllBytes(bomFile), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException(bomFile + " is not a CycloneDX JSON object");
        Map<String, Object> bom = (Map<String, Object>) parsed;
        if (!"CycloneDX".equals(bom.get("bomFormat"))) throw new IOException(bomFile + " is not a CycloneDX BOM");
        List<Object> components = (List<Object>) bom.computeIfAbsent("components", k -> new ArrayList<>());
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) throw new IOException(dir + " is not a readable directory");
        Arrays.sort(jars);
        List<File> all = new ArrayList<>(Arrays.asList(jars));
        // the FIPS crypto modules, unmodified in crypto/ (CryptoBoot): listed as crypto/<jar>
        File[] modules = new File(dir, "crypto").listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (modules != null) {
            Arrays.sort(modules);
            all.addAll(Arrays.asList(modules));
        }
        List<String> refs = new ArrayList<>();
        for (File jar : all) {
            String entry = jar.getParentFile().equals(dir) ? jar.getName() : "crypto/" + jar.getName();
            Map<String, Object> c = component(jar, entry, ref);
            String bomRef = (String) c.get("bom-ref");
            components.removeIf(o -> o instanceof Map && bomRef.equals(((Map<String, Object>) o).get("bom-ref")));
            components.add(c);
            refs.add(bomRef);
        }
        String root = null;
        Object meta = bom.get("metadata");
        if (meta instanceof Map && ((Map<String, Object>) meta).get("component") instanceof Map) {
            root = (String) ((Map<String, Object>) ((Map<String, Object>) meta).get("component")).get("bom-ref");
        }
        if (root != null) {
            List<Object> deps = (List<Object>) bom.computeIfAbsent("dependencies", k -> new ArrayList<>());
            Map<String, Object> rootDeps = null;
            for (Object o : deps) {
                if (o instanceof Map && root.equals(((Map<String, Object>) o).get("ref"))) rootDeps = (Map<String, Object>) o;
            }
            if (rootDeps == null) {
                rootDeps = new LinkedHashMap<>();
                rootDeps.put("ref", root);
                deps.add(rootDeps);
            }
            List<Object> on = (List<Object>) rootDeps.computeIfAbsent("dependsOn", k -> new ArrayList<>());
            for (String r : refs) if (!on.contains(r)) on.add(r);
        }
        write(bomFile, bom);
        return refs.size();
    }

    /** The component for one embedded jar. */
    static Map<String, Object> component(File jar) throws IOException {
        return component(jar, InlinedLibraries.Reference.NONE);
    }

    /** The component for one embedded jar, its inlined libraries named by content against {@code ref} where they can be. */
    static Map<String, Object> component(File jar, InlinedLibraries.Reference ref) throws IOException {
        return component(jar, jar.getName(), ref);
    }

    /**
     * As {@link #component(File, InlinedLibraries.Reference)}, for the embedded file {@code entry} (its path in the agent
     * jar). A jar that is exactly a reference artifact (the same bytes: the crypto modules, which carry no pom.properties)
     * has that artifact's coordinates and carries no other library.
     */
    static Map<String, Object> component(File jar, String entry, InlinedLibraries.Reference ref) throws IOException {
        byte[] bytes = Files.readAllBytes(jar.toPath());
        InlinedLibraries.Reference.Artifact whole = ref == null || ref.isEmpty() ? null : ref.byJar(InlinedLibraries.sha256Hex(bytes));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "library");
        c.put("bom-ref", "embedded:" + entry);
        List<Map<String, Object>> nested = new ArrayList<>();
        String bsn = null, bundleVersion = null;
        Properties own = null;
        InlinedLibraries.Context ctx = new InlinedLibraries.Context();
        Map<String, Integer> classPackages = new LinkedHashMap<>();
        Map<String, String> classSha = ref == null || ref.isEmpty() ? null : new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar, false)) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                bsn = attr(mf, "Bundle-SymbolicName");
                if (bsn != null && bsn.indexOf(';') >= 0) bsn = bsn.substring(0, bsn.indexOf(';')).trim();
                bundleVersion = attr(mf, "Bundle-Version");
                InlinedLibraries.readHeaders(ctx, attr(mf, "Export-Package"), attr(mf, "Embedded-Artifacts"));
            }
            ctx.bundleVersion = bundleVersion;
            ctx.ownGroup(bsn);
            List<Properties> poms = new ArrayList<>();
            for (JarEntry e : java.util.Collections.list(jf.entries())) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                String pkg = InlinedLibraries.classPackage(n);
                if (pkg != null) {
                    classPackages.merge(pkg, 1, Integer::sum);
                    if (classSha != null) {
                        try (InputStream in = jf.getInputStream(e)) {
                            classSha.put(n, InlinedLibraries.sha256Hex(in.readAllBytes()));
                        }
                    }
                } else if (n.equals("META-INF/DEPENDENCIES")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        InlinedLibraries.readDependencies(ctx, new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8));
                    }
                } else if (n.startsWith("META-INF/maven/") && n.endsWith("/pom.properties")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        Properties p = new Properties();
                        p.load(in);
                        poms.add(p);
                    }
                } else if (n.toLowerCase().endsWith(".jar")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        byte[] inner = in.readAllBytes();
                        Map<String, Object> nc = nestedJar(jar.getName(), n, inner, ctx);
                        nested.add(nc);
                        if (nc.get("group") != null) ctx.nestedCoordinates.put(nc.get("group") + ":" + nc.get("name"), n);
                        ctx.nestedPackages.addAll(packagesOf(inner));
                    }
                }
            }
            own = ownCoordinates(poms, bsn);
            if (own != null) ctx.ownGroup(own.getProperty("groupId"));
            for (Properties p : poms) {
                ctx.listedCoordinates.add(p.getProperty("groupId") + ":" + p.getProperty("artifactId"));
                if (p != own) {
                    // another artifact's pom.properties accounts for that artifact's packages only, not its whole group:
                    // siddhi-core's left siddhi-query-api, -query-compiler and -annotations unlisted
                    ctx.artifactRoot(p.getProperty("groupId"), p.getProperty("artifactId"));
                    nested.add(maven(jar.getName(), p, null));
                }
            }
            if (whole == null) {
                List<InlinedLibraries.Found> found = InlinedLibraries.find(classPackages, ctx, path -> entryBytes(jf, path));
                for (InlinedLibraries.Found f : InlinedLibraries.identifyByContent(found, classSha, ref, ctx)) {
                    nested.add(inlined(jar.getName(), f));
                }
            }
        }
        if (own == null && whole != null) {
            c.put("group", whole.group);
            c.put("name", whole.artifact);
            c.put("version", whole.version);
            c.put("purl", whole.purl());
        } else if (own != null) {
            c.put("group", own.getProperty("groupId"));
            c.put("name", own.getProperty("artifactId"));
            c.put("version", own.getProperty("version"));
            c.put("purl", purl(own));
        } else {
            c.put("name", bsn != null ? bsn : jar.getName().replaceAll("\\.jar$", ""));
            c.put("version", bundleVersion != null ? bundleVersion : "unknown");
        }
        c.put("description", "Embedded in the agent jar as " + entry + (whole != null
                ? " (unmodified; identified as " + whole.gav() + " by its SHA-256)" : ""));
        c.put("hashes", hashesOf(bytes, "SHA-256", "SHA-384", "SHA-512"));
        List<Object> props = new ArrayList<>();
        props.add(prop("cresco:embedded-file", entry));
        if (entry.startsWith("crypto/")) props.add(prop("cresco:crypto-module", "true"));
        if (bsn != null) props.add(prop("osgi:Bundle-SymbolicName", bsn));
        if (bundleVersion != null) props.add(prop("osgi:Bundle-Version", bundleVersion));
        c.put("properties", props);
        if (!nested.isEmpty()) c.put("components", new ArrayList<Object>(nested));
        return c;
    }

    /** The jar's own coordinates: the only pom.properties, or the one matching its Bundle-SymbolicName. */
    private static Properties ownCoordinates(List<Properties> poms, String bsn) {
        if (poms.size() == 1) return poms.get(0);
        if (bsn == null) return null;
        for (Properties p : poms) {
            String g = p.getProperty("groupId", ""), a = p.getProperty("artifactId", "");
            if (bsn.equals(g + "." + a) || bsn.equals(a) || bsn.endsWith("." + a)) return p;
        }
        return null;
    }

    /** The component for a library {@code outer} inlines as bare classes. */
    private static Map<String, Object> inlined(String outer, InlinedLibraries.Found f) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "library");
        InlinedLibraries.Match m = f.match;
        boolean known = m.known != null;
        c.put("bom-ref", "embedded:" + outer + "!inlined:" + m.key());
        if (known) c.put("group", m.group);
        c.put("name", known ? m.artifact : f.rootName());
        c.put("version", f.version);
        c.put("description", "Classes inlined in " + outer + " without Maven metadata, " + (f.versionSource.startsWith("content:")
                ? "identified by content: byte-identical to the Maven artifact's" : known
                ? "identified by their packages" : !f.declared.isEmpty()
                ? "listed by their packages, with the artifacts " + outer + " declares under them"
                : "not identified: listed by their packages"));
        if (known) c.put("purl", "pkg:maven/" + m.group + "/" + m.artifact + ("unknown".equals(f.version) ? "" : "@" + f.version));
        List<Object> props = new ArrayList<>();
        props.add(prop("cresco:embedded-file", outer));
        props.add(prop("cresco:inlined-package-root", f.rootName()));
        props.add(prop("cresco:inlined-packages", String.join(",", f.packages.keySet())));
        props.add(prop("cresco:inlined-classes", Integer.toString(f.classes())));
        props.add(prop("cresco:identified", Boolean.toString(f.identified())));
        props.add(prop("cresco:version-source", f.versionSource));
        if (!f.otherVersions.isEmpty()) props.add(prop("cresco:version-disagreement", String.join("; ", f.otherVersions)));
        String nestedCopy = known ? f.nestedCopy : null;
        if (nestedCopy != null) props.add(prop("cresco:second-copy-of", outer + "!/" + nestedCopy));
        c.put("properties", props);
        if (!f.declared.isEmpty()) {
            List<Object> members = new ArrayList<>();
            for (String[] d : f.declared) {
                Map<String, Object> mc = new LinkedHashMap<>();
                mc.put("type", "library");
                mc.put("bom-ref", "embedded:" + outer + "!inlined:" + d[0] + ":" + d[1]);
                mc.put("group", d[0]);
                mc.put("name", d[1]);
                mc.put("version", d[2]);
                mc.put("purl", "pkg:maven/" + d[0] + "/" + d[1] + "@" + d[2]);
                mc.put("properties", new ArrayList<Object>(List.of(prop("cresco:embedded-file", outer), prop("cresco:declared-in", d[3]))));
                members.add(mc);
            }
            c.put("components", members);
        }
        return c;
    }

    private static byte[] entryBytes(JarFile jf, String path) {
        JarEntry e = jf.getJarEntry(path);
        if (e == null || e.getSize() > 4L << 20) return null;
        try (InputStream in = jf.getInputStream(e)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            return null;
        }
    }

    /** The packages (slash form) of the classes in a jar's bytes. */
    private static java.util.Set<String> packagesOf(byte[] jarBytes) throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            JarEntry e;
            while ((e = in.getNextJarEntry()) != null) {
                String pkg = InlinedLibraries.classPackage(e.getName());
                if (pkg != null) out.add(pkg);
            }
        }
        return out;
    }

    private static Map<String, Object> nestedJar(String outer, String path, byte[] bytes, InlinedLibraries.Context ctx) throws IOException {
        Properties found = null;
        String bsn = null, version = null;
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(bytes))) {
            Manifest mf = in.getManifest();
            if (mf != null) {
                bsn = attr(mf, "Bundle-SymbolicName");
                version = attr(mf, "Bundle-Version");
                if (version == null) version = attr(mf, "Implementation-Version");
                if (bsn == null) bsn = attr(mf, "Implementation-Title");
            }
            JarEntry e;
            while ((e = in.getNextJarEntry()) != null) {
                if (e.getName().startsWith("META-INF/maven/") && e.getName().endsWith("/pom.properties") && found == null) {
                    Properties p = new Properties();
                    p.load(new java.io.ByteArrayInputStream(readAll(in)));
                    found = p;
                }
            }
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> c;
        if (found == null && ctx != null) {
            // no pom.properties inside: the bundle's own Embedded-Artifacts header names this file's coordinates
            String[] gav = ctx.embeddedFileCoordinates(file);
            if (gav != null) {
                Properties p = new Properties();
                p.setProperty("groupId", gav[0]);
                p.setProperty("artifactId", gav[1]);
                p.setProperty("version", gav[2]);
                found = p;
            }
        }
        if (found != null) {
            c = maven(outer, found, path);
        } else {
            c = new LinkedHashMap<>();
            c.put("type", "library");
            c.put("bom-ref", "embedded:" + outer + "!" + path);
            String name = bsn != null ? bsn : file.replaceAll("\\.jar$", "");
            if (name.indexOf(';') >= 0) name = name.substring(0, name.indexOf(';')).trim();
            c.put("name", name);
            c.put("version", version != null ? version : "unknown");
        }
        c.put("hashes", hashesOf(bytes, "SHA-256"));
        List<Object> props = new ArrayList<>();
        props.add(prop("cresco:embedded-file", outer + "!/" + path));
        c.put("properties", props);
        return c;
    }

    private static Map<String, Object> maven(String outer, Properties p, String path) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "library");
        String gav = p.getProperty("groupId") + ":" + p.getProperty("artifactId") + ":" + p.getProperty("version");
        c.put("bom-ref", "embedded:" + outer + "!" + (path != null ? path : gav));
        c.put("group", p.getProperty("groupId"));
        c.put("name", p.getProperty("artifactId"));
        c.put("version", p.getProperty("version"));
        c.put("purl", purl(p));
        return c;
    }

    private static String purl(Properties p) {
        return "pkg:maven/" + p.getProperty("groupId") + "/" + p.getProperty("artifactId") + "@" + p.getProperty("version");
    }

    private static String attr(Manifest mf, String name) {
        String v = mf.getMainAttributes().getValue(new Attributes.Name(name));
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Map<String, Object> prop(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    static List<Object> hashesOf(byte[] data, String... algs) {
        List<Object> out = new ArrayList<>();
        for (String alg : algs) {
            try {
                byte[] d = MessageDigest.getInstance(alg).digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format("%02x", b & 0xff));
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("alg", alg);
                h.put("content", sb.toString());
                out.add(h);
            } catch (Exception e) {
                throw new IllegalStateException(alg + " unavailable", e);
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** A small JSON reader and writer (objects keep their key order), enough for a CycloneDX BOM. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) throws IOException {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IOException("trailing data at " + j.i);
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() throws IOException {
            if (i >= s.length()) throw new IOException("unexpected end of JSON");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String word) throws IOException {
            if (!s.startsWith(word, i)) throw new IOException("expected " + word + " at " + i);
            i += word.length();
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IOException("expected : at " + (i - 1));
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IOException("expected , or } at " + (i - 1));
            }
        }

        private List<Object> array() throws IOException {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new IOException("expected , or ] at " + (i - 1));
            }
        }

        private String string() throws IOException {
            if (s.charAt(i) != '"') throw new IOException("expected a string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw new IOException("bad escape at " + (i - 1));
                }
            }
        }

        private Object number() throws IOException {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IOException("unexpected character at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(v, sb, 0);
            return sb.append('\n').toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object v, StringBuilder sb, int indent) {
            if (v == null) { sb.append("null"); return; }
            if (v instanceof String) { quote((String) v, sb); return; }
            if (v instanceof Number || v instanceof Boolean) { sb.append(v); return; }
            String pad = "  ".repeat(indent + 1), end = "  ".repeat(indent);
            if (v instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) { sb.append("{ }"); return; }
                sb.append("{\n");
                int k = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    sb.append(pad);
                    quote(e.getKey(), sb);
                    sb.append(" : ");
                    write(e.getValue(), sb, indent + 1);
                    if (++k < m.size()) sb.append(',');
                    sb.append('\n');
                }
                sb.append(end).append('}');
                return;
            }
            List<Object> a = (List<Object>) v;
            if (a.isEmpty()) { sb.append("[ ]"); return; }
            sb.append("[\n");
            for (int k = 0; k < a.size(); k++) {
                sb.append(pad);
                write(a.get(k), sb, indent + 1);
                if (k + 1 < a.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(end).append(']');
        }

        private static void quote(String str, StringBuilder sb) {
            sb.append('"');
            for (int k = 0; k < str.length(); k++) {
                char c = str.charAt(k);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
