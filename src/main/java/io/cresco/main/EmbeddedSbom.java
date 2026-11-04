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
 * Completes the agent's CycloneDX SBOM with what the agent jar really ships. The CycloneDX Maven plugin lists the
 * agent's Maven dependencies, but most of the agent is the OSGi bundles embedded as resources (controller, library,
 * wsapi, the Felix bundles ...), and each of those embeds further libraries. This adds, to the plugin's JSON BOM:
 * <ul>
 *   <li>one component per embedded jar ({@code src/main/resources/*.jar}): its Maven coordinates and purl when the
 *       jar names them ({@code META-INF/maven/.../pom.properties}), else its Bundle-SymbolicName and Bundle-Version;
 *       SHA-256, SHA-384 and SHA-512 of the jar; the embedded file name as a property;</li>
 *   <li>under each, the libraries it carries: nested jars (with their own coordinates and SHA-256) and inlined
 *       dependencies named by further {@code pom.properties};</li>
 *   <li>the dependency edges from the agent to each embedded jar.</li>
 * </ul>
 * Runs in the build (package, after the CycloneDX plugin; see pom.xml). Usage:
 * {@code java io.cresco.main.EmbeddedSbom <bom.json> <dir-with-embedded-jars>}.
 */
public final class EmbeddedSbom {

    private EmbeddedSbom() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: EmbeddedSbom <bom.json> <dir-with-embedded-jars>");
            System.exit(2);
        }
        Path bom = Paths.get(args[0]);
        int n = complete(bom, new File(args[1]));
        System.out.println("EmbeddedSbom: added " + n + " embedded jar(s) to " + bom);
    }

    /** Add the embedded jars in {@code dir} to the BOM file; returns how many were added. */
    @SuppressWarnings("unchecked")
    static int complete(Path bomFile, File dir) throws IOException {
        Object parsed = Json.parse(new String(Files.readAllBytes(bomFile), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException(bomFile + " is not a CycloneDX JSON object");
        Map<String, Object> bom = (Map<String, Object>) parsed;
        if (!"CycloneDX".equals(bom.get("bomFormat"))) throw new IOException(bomFile + " is not a CycloneDX BOM");
        List<Object> components = (List<Object>) bom.computeIfAbsent("components", k -> new ArrayList<>());
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) throw new IOException(dir + " is not a readable directory");
        Arrays.sort(jars);
        List<String> refs = new ArrayList<>();
        for (File jar : jars) {
            Map<String, Object> c = component(jar);
            String ref = (String) c.get("bom-ref");
            components.removeIf(o -> o instanceof Map && ref.equals(((Map<String, Object>) o).get("bom-ref")));
            components.add(c);
            refs.add(ref);
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
        Path tmp = bomFile.resolveSibling(bomFile.getFileName() + ".tmp");
        Files.write(tmp, Json.write(bom).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, bomFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return refs.size();
    }

    /** The component for one embedded jar. */
    static Map<String, Object> component(File jar) throws IOException {
        byte[] bytes = Files.readAllBytes(jar.toPath());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "library");
        c.put("bom-ref", "embedded:" + jar.getName());
        List<Map<String, Object>> nested = new ArrayList<>();
        String bsn = null, bundleVersion = null;
        Properties own = null;
        try (JarFile jf = new JarFile(jar, false)) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                bsn = attr(mf, "Bundle-SymbolicName");
                if (bsn != null && bsn.indexOf(';') >= 0) bsn = bsn.substring(0, bsn.indexOf(';')).trim();
                bundleVersion = attr(mf, "Bundle-Version");
            }
            List<Properties> poms = new ArrayList<>();
            for (JarEntry e : java.util.Collections.list(jf.entries())) {
                String n = e.getName();
                if (n.startsWith("META-INF/maven/") && n.endsWith("/pom.properties")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        Properties p = new Properties();
                        p.load(in);
                        poms.add(p);
                    }
                } else if (n.toLowerCase().endsWith(".jar") && !e.isDirectory()) {
                    try (InputStream in = jf.getInputStream(e)) {
                        nested.add(nestedJar(jar.getName(), n, in.readAllBytes()));
                    }
                }
            }
            own = ownCoordinates(poms, bsn);
            for (Properties p : poms) {
                if (p != own) nested.add(maven(jar.getName(), p, null));
            }
        }
        if (own != null) {
            c.put("group", own.getProperty("groupId"));
            c.put("name", own.getProperty("artifactId"));
            c.put("version", own.getProperty("version"));
            c.put("purl", purl(own));
        } else {
            c.put("name", bsn != null ? bsn : jar.getName().replaceAll("\\.jar$", ""));
            c.put("version", bundleVersion != null ? bundleVersion : "unknown");
        }
        c.put("description", "Embedded in the agent jar as " + jar.getName());
        c.put("hashes", hashes(bytes, "SHA-256", "SHA-384", "SHA-512"));
        List<Object> props = new ArrayList<>();
        props.add(prop("cresco:embedded-file", jar.getName()));
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

    private static Map<String, Object> nestedJar(String outer, String path, byte[] bytes) throws IOException {
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
        c.put("hashes", hashes(bytes, "SHA-256"));
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

    private static List<Object> hashes(byte[] data, String... algs) {
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
