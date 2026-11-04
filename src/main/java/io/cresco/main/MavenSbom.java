package io.cresco.main;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The agent's own part of its CycloneDX 1.6 SBOM: the agent and the Maven dependencies it ships (the runtime classpath
 * the assembly bundles). The build writes it itself, with no Maven plugin beyond those it already needs, so any build that
 * can build the agent, online or offline, writes the SBOM. Coordinates come from the local repository's layout (or the
 * jar's pom.properties); licenses and dependency edges from the POMs Maven has already resolved into that repository.
 * {@link EmbeddedSbom} then adds the embedded bundles.
 */
final class MavenSbom {

    private MavenSbom() {}

    /** One resolved artifact. */
    static final class Artifact {
        final String group, name, version, classifier;
        final Path file;
        String ref;

        Artifact(String group, String name, String version, String classifier, Path file) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.classifier = classifier;
            this.file = file;
        }

        String ga() { return group + ":" + name; }

        String purl() {
            return "pkg:maven/" + group + "/" + name + "@" + version + (classifier != null ? "?classifier=" + classifier : "");
        }
    }

    /**
     * The BOM for project {@code gav} (group:artifact:version) whose POM is {@code projectPom}, shipping the jars of
     * {@code classpath} (directories are skipped) resolved into {@code localRepo}.
     */
    static Map<String, Object> create(String gav, Path projectPom, Path localRepo, List<Path> classpath) throws IOException {
        String[] p = gav.split(":");
        if (p.length != 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) {
            throw new IOException("the project coordinates must be group:artifact:version, not " + gav);
        }
        if (!Files.isRegularFile(projectPom)) throw new IOException("the project POM " + projectPom + " does not exist");
        Path repo = localRepo.toAbsolutePath().normalize();

        List<Artifact> artifacts = new ArrayList<>();
        Map<String, Artifact> byGa = new LinkedHashMap<>();
        for (Path entry : classpath) {
            if (Files.isDirectory(entry)) continue;
            if (!Files.isRegularFile(entry)) {
                // Maven lists the output directory even when the project has no classes; a missing jar is an error
                if (!entry.getFileName().toString().endsWith(".jar")) continue;
                throw new IOException("the runtime classpath names " + entry + ", which does not exist");
            }
            Artifact a = fromRepository(repo, entry.toAbsolutePath().normalize());
            if (a == null) a = fromPomProperties(entry);
            if (a == null) {
                throw new IOException(entry + " is on the runtime classpath but is neither in the local repository " + repo
                        + " nor names its Maven coordinates; the SBOM cannot list it");
            }
            a.ref = a.purl();
            artifacts.add(a);
            byGa.putIfAbsent(a.ga(), a);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "application");
        root.put("bom-ref", "pkg:maven/" + p[0] + "/" + p[1] + "@" + p[2]);
        root.put("group", p[0]);
        root.put("name", p[1]);
        root.put("version", p[2]);
        Pom projectModel = Pom.read(projectPom);
        Chain projectChain = new Chain(repo, projectModel);
        if (projectModel != null && projectModel.description != null) root.put("description", projectModel.description);
        List<Object> rootLicenses = projectChain.licenses();
        if (!rootLicenses.isEmpty()) root.put("licenses", rootLicenses);
        root.put("purl", root.get("bom-ref"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "application");
        tool.put("group", "io.cresco");
        tool.put("name", "agent-sbom");
        tool.put("version", p[2]);
        tool.put("description", "The agent build's SBOM writer (io.cresco.main.MavenSbom and EmbeddedSbom)");
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("components", new ArrayList<Object>(List.of(tool)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        metadata.put("tools", tools);
        metadata.put("component", root);

        List<Object> components = new ArrayList<>();
        Map<String, Chain> chains = new HashMap<>();
        for (Artifact a : artifacts) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "library");
            c.put("bom-ref", a.ref);
            c.put("group", a.group);
            c.put("name", a.name);
            c.put("version", a.version);
            c.put("scope", "required");
            c.put("hashes", EmbeddedSbom.hashesOf(Files.readAllBytes(a.file), "SHA-256", "SHA-384", "SHA-512"));
            Chain chain = new Chain(repo, Pom.read(pomPath(repo, a.group, a.name, a.version)));
            chains.put(a.ref, chain);
            List<Object> lic = chain.licenses();
            if (!lic.isEmpty()) c.put("licenses", lic);
            c.put("purl", a.purl());
            components.add(c);
        }

        // edges: the project's dependencies, then each artifact's own (from its POM), among what was resolved
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        String rootRef = (String) root.get("bom-ref");
        edges.put(rootRef, new LinkedHashSet<>());
        for (String ga : projectChain.dependencies(true)) {
            Artifact a = byGa.get(ga);
            if (a != null) edges.get(rootRef).add(a.ref);
        }
        for (Artifact a : artifacts) {
            Set<String> on = new LinkedHashSet<>();
            for (String ga : chains.get(a.ref).dependencies(false)) {
                Artifact d = byGa.get(ga);
                if (d != null && d != a) on.add(d.ref);
            }
            edges.put(a.ref, on);
        }
        // anything resolved but not reached from the project (managed or imported in ways a POM walk does not see)
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(rootRef));
        while (!todo.isEmpty()) {
            String r = todo.pop();
            if (!reached.add(r)) continue;
            todo.addAll(edges.getOrDefault(r, Set.of()));
        }
        for (Artifact a : artifacts) if (!reached.contains(a.ref)) edges.get(rootRef).add(a.ref);
        List<Object> deps = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ref", e.getKey());
            d.put("dependsOn", new ArrayList<Object>(e.getValue()));
            deps.add(d);
        }

        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.6");
        bom.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
        bom.put("version", 1);
        bom.put("metadata", metadata);
        bom.put("components", components);
        bom.put("dependencies", deps);
        return bom;
    }

    /** The artifact at {@code jar} by the local repository layout (group/path/artifact/version/artifact-version[-classifier].ext). */
    static Artifact fromRepository(Path repo, Path jar) {
        if (!jar.startsWith(repo)) return null;
        Path rel = repo.relativize(jar);
        int n = rel.getNameCount();
        if (n < 4) return null;
        String file = rel.getName(n - 1).toString();
        String version = rel.getName(n - 2).toString();
        String name = rel.getName(n - 3).toString();
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < n - 3; i++) {
            if (i > 0) group.append('.');
            group.append(rel.getName(i));
        }
        int dot = file.lastIndexOf('.');
        if (dot < 0) return null;
        String base = file.substring(0, dot);
        String prefix = name + "-";
        if (!base.startsWith(prefix)) return null;
        String rest = base.substring(prefix.length());
        String classifier = null;
        String stem = version.endsWith("-SNAPSHOT") ? version.substring(0, version.length() - "SNAPSHOT".length()) : version;
        if (rest.equals(version)) {
            classifier = null;
        } else if (rest.startsWith(version + "-")) {
            classifier = rest.substring(version.length() + 1);
        } else if (version.endsWith("-SNAPSHOT") && rest.startsWith(stem)) {
            // a timestamped snapshot: artifact-1.3-20261104.120000-1[-classifier]
            String[] parts = rest.substring(stem.length()).split("-", 3);
            classifier = parts.length == 3 ? parts[2] : null;
        } else {
            return null;
        }
        return new Artifact(group.toString(), name, version, classifier, jar);
    }

    /** The artifact by the jar's only pom.properties, or null. */
    static Artifact fromPomProperties(Path jar) throws IOException {
        Properties found = null;
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            for (JarEntry e : java.util.Collections.list(jf.entries())) {
                if (e.getName().startsWith("META-INF/maven/") && e.getName().endsWith("/pom.properties")) {
                    if (found != null) return null;
                    Properties p = new Properties();
                    try (InputStream in = jf.getInputStream(e)) {
                        p.load(in);
                    }
                    found = p;
                }
            }
        } catch (IOException e) {
            throw new IOException("cannot read " + jar + ": " + e.getMessage(), e);
        }
        if (found == null || found.getProperty("groupId") == null || found.getProperty("artifactId") == null
                || found.getProperty("version") == null) return null;
        return new Artifact(found.getProperty("groupId"), found.getProperty("artifactId"), found.getProperty("version"), null, jar);
    }

    static Path pomPath(Path repo, String group, String name, String version) {
        return repo.resolve(group.replace('.', '/')).resolve(name).resolve(version).resolve(name + "-" + version + ".pom");
    }

    /** The parts of a POM the SBOM uses. */
    static final class Pom {
        String groupId, artifactId, version, description;
        String parentGroup, parentArtifact, parentVersion;
        final Map<String, String> properties = new HashMap<>();
        final List<String[]> licenses = new ArrayList<>();          // {name, url}
        final List<String[]> dependencies = new ArrayList<>();      // {groupId, artifactId, scope, optional}

        /** The POM at {@code file}, or null when it is missing or not a readable POM (the SBOM then lacks what it would add). */
        static Pom read(Path file) {
            if (file == null || !Files.isRegularFile(file)) return null;
            try (InputStream in = Files.newInputStream(file)) {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                DocumentBuilder b = f.newDocumentBuilder();
                b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());   // fatal errors throw; nothing printed
                Document d = b.parse(in);
                Element project = d.getDocumentElement();
                if (project == null || !"project".equals(local(project))) return null;
                Pom pom = new Pom();
                pom.artifactId = text(project, "artifactId");
                pom.groupId = text(project, "groupId");
                pom.version = text(project, "version");
                pom.description = text(project, "description");
                Element parent = child(project, "parent");
                if (parent != null) {
                    pom.parentGroup = text(parent, "groupId");
                    pom.parentArtifact = text(parent, "artifactId");
                    pom.parentVersion = text(parent, "version");
                    if (pom.groupId == null) pom.groupId = pom.parentGroup;
                    if (pom.version == null) pom.version = pom.parentVersion;
                }
                Element props = child(project, "properties");
                if (props != null) {
                    for (Element e : children(props, null)) pom.properties.put(local(e), e.getTextContent().trim());
                }
                Element lics = child(project, "licenses");
                if (lics != null) {
                    for (Element l : children(lics, "license")) {
                        String name = text(l, "name"), url = text(l, "url");
                        if (name != null || url != null) pom.licenses.add(new String[]{name, url});
                    }
                }
                Element deps = child(project, "dependencies");
                if (deps != null) {
                    for (Element dep : children(deps, "dependency")) {
                        pom.dependencies.add(new String[]{text(dep, "groupId"), text(dep, "artifactId"), text(dep, "scope"), text(dep, "optional")});
                    }
                }
                return pom;
            } catch (Exception e) {
                return null;
            }
        }

        private static String local(Node n) {
            String name = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
            int colon = name.indexOf(':');
            return colon < 0 ? name : name.substring(colon + 1);
        }

        private static Element child(Element e, String name) {
            List<Element> c = children(e, name);
            return c.isEmpty() ? null : c.get(0);
        }

        private static List<Element> children(Element e, String name) {
            List<Element> out = new ArrayList<>();
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element el && (name == null || name.equals(local(el)))) out.add(el);
            }
            return out;
        }

        private static String text(Element e, String name) {
            Element c = child(e, name);
            if (c == null) return null;
            String t = c.getTextContent().trim();
            return t.isEmpty() ? null : t;
        }
    }

    /** A POM and its parents, as far as the local repository has them. */
    static final class Chain {
        final List<Pom> poms = new ArrayList<>();

        Chain(Path repo, Pom pom) {
            Pom p = pom;
            Set<String> seen = new LinkedHashSet<>();
            while (p != null && poms.size() < 16) {
                poms.add(p);
                if (p.parentGroup == null || p.parentArtifact == null || p.parentVersion == null) break;
                if (!seen.add(p.parentGroup + ":" + p.parentArtifact + ":" + p.parentVersion)) break;
                p = Pom.read(pomPath(repo, p.parentGroup, p.parentArtifact, p.parentVersion));
            }
        }

        /** The nearest POM's licenses (a child's licenses replace its parent's), as CycloneDX license choices. */
        List<Object> licenses() {
            List<Object> out = new ArrayList<>();
            for (Pom p : poms) {
                if (p.licenses.isEmpty()) continue;
                for (String[] l : p.licenses) {
                    Map<String, Object> lic = new LinkedHashMap<>();
                    lic.put("name", l[0] != null ? l[0] : l[1]);
                    if (l[1] != null && l[1].matches("https?://\\S+")) lic.put("url", l[1]);
                    Map<String, Object> choice = new LinkedHashMap<>();
                    choice.put("license", lic);
                    out.add(choice);
                }
                break;
            }
            return out;
        }

        /**
         * The group:artifact of the dependencies the chain declares that are shipped: not test or provided scope, and
         * (unless {@code includeOptional}, for the project itself) not optional.
         */
        List<String> dependencies(boolean includeOptional) {
            List<String> out = new ArrayList<>();
            for (Pom p : poms) {
                for (String[] d : p.dependencies) {
                    String scope = d[2] == null ? "compile" : resolve(d[2]);
                    if (!scope.equals("compile") && !scope.equals("runtime") && !scope.equals("system")) continue;
                    if (!includeOptional && "true".equals(resolve(d[3] == null ? "false" : d[3]))) continue;
                    String g = d[0] == null ? null : resolve(d[0]);
                    String a = d[1] == null ? null : resolve(d[1]);
                    if (g == null || a == null || g.contains("${") || a.contains("${")) continue;
                    out.add(g + ":" + a);
                }
            }
            return out;
        }

        /** ${...} references from the chain's properties and project coordinates (the nearest POM's first). */
        String resolve(String v) {
            String s = v;
            for (int guard = 0; guard < 8 && s.contains("${"); guard++) {
                int start = s.indexOf("${"), end = s.indexOf('}', start);
                if (end < 0) break;
                String key = s.substring(start + 2, end);
                String val = lookup(key);
                if (val == null) break;
                s = s.substring(0, start) + val + s.substring(end + 1);
            }
            return s;
        }

        private String lookup(String key) {
            Pom first = poms.isEmpty() ? null : poms.get(0);
            if (first != null) {
                switch (key) {
                    case "project.groupId": case "pom.groupId": case "groupId": return first.groupId;
                    case "project.artifactId": case "pom.artifactId": case "artifactId": return first.artifactId;
                    case "project.version": case "pom.version": case "version": return first.version;
                    case "project.parent.groupId": case "parent.groupId": return first.parentGroup;
                    case "project.parent.version": case "parent.version": return first.parentVersion;
                    default: break;
                }
            }
            for (Pom p : poms) {
                String v = p.properties.get(key);
                if (v != null) return v;
            }
            return null;
        }
    }
}
