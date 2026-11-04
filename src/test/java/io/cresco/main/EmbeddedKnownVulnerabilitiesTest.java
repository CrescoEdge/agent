package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build fails when an embedded jar's SBOM tree (the jar, its nested jars, the libraries it inlines and declares)
 * holds a component on the offline known-vulnerable list ({@code known-vulnerable-components.txt}), unless a dated
 * waiver ({@code known-vulnerable-waivers.txt}) covers it. An expired waiver, or one that matches nothing, fails too.
 */
class EmbeddedKnownVulnerabilitiesTest {

    record Rule(String group, String artifact, List<String> fixed, String ids) { }

    record Waiver(String coordinates, String jar, LocalDate until, String reason) { }

    static List<String> lines(String resource) throws IOException {
        try (InputStream in = EmbeddedKnownVulnerabilitiesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            List<String> out = new ArrayList<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = l.strip();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
            return out;
        }
    }

    static List<Rule> rules() throws IOException {
        List<Rule> out = new ArrayList<>();
        for (String l : lines("known-vulnerable-components.txt")) {
            String[] f = l.split("\\s+");
            assertTrue(f.length >= 3, l);
            String[] ga = f[0].split(":");
            out.add(new Rule(ga[0], ga[1], List.of(f[1].split(",")), f[2]));
        }
        return out;
    }

    static List<Waiver> waivers() throws IOException {
        List<Waiver> out = new ArrayList<>();
        for (String l : lines("known-vulnerable-waivers.txt")) {
            String[] f = l.split("\\s+", 4);
            assertTrue(f.length == 4, "waiver needs coordinates@version, jar, until, reason: " + l);
            out.add(new Waiver(f[0], f[1], LocalDate.parse(f[2]), f[3]));
        }
        return out;
    }

    /** Maven-like comparison: numeric parts numerically; alpha/beta/rc/milestone/snapshot below the release; other qualifiers (jre, final) ignored. */
    static int compare(String a, String b) {
        String[] x = a.toLowerCase().split("[.\\-_]"), y = b.toLowerCase().split("[.\\-_]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Long.compare(rank(i < x.length ? x[i] : null), rank(i < y.length ? y[i] : null));
            if (c != 0) return c;
            if (i < x.length && i < y.length && isNum(x[i]) && isNum(y[i])) {
                c = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));
                if (c != 0) return c;
            }
        }
        return 0;
    }

    static boolean isNum(String s) { return s != null && s.matches("[0-9]+"); }

    /** Pre-releases below a release, a release (or an ignored qualifier) as 0 more, numbers compared separately. */
    static long rank(String t) {
        if (t == null) return 0;
        if (isNum(t)) return 1;
        if (t.matches("(alpha|a|beta|b|milestone|m|rc|cr|pre|preview|ea|snapshot)[0-9]*")) return -1;
        return 0;
    }

    static String line(String v) {
        String[] p = v.split("[.\\-_]");
        return p.length >= 2 && isNum(p[0]) && isNum(p[1]) ? p[0] + "." + p[1] : p[0];
    }

    static boolean vulnerable(Rule r, String version) {
        if (version == null || version.equals("unknown")) return false;
        if (r.fixed().equals(List.of("*"))) return true;
        String newest = r.fixed().get(0);
        for (String f : r.fixed()) {
            if (line(f).equals(line(version))) return compare(version, f) < 0;
            if (compare(f, newest) > 0) newest = f;
        }
        return compare(version, newest) < 0;
    }

    @SuppressWarnings("unchecked")
    static void walk(List<Object> comps, String jar, List<String[]> out) {
        for (Object o : comps) {
            Map<String, Object> c = (Map<String, Object>) o;
            if (c.get("group") != null) out.add(new String[]{jar, (String) c.get("group"), (String) c.get("name"), (String) c.get("version")});
            if (c.get("components") instanceof List) walk((List<Object>) c.get("components"), jar, out);
        }
    }

    static List<String[]> embedded() throws Exception {
        File dir = new File(HostApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        assertNotNull(jars);
        List<String[]> out = new ArrayList<>();
        for (File j : jars) walk(List.of((Object) EmbeddedSbom.component(j)), j.getName(), out);
        return out;
    }

    @Test
    void versionsCompareLikeMaven() {
        assertTrue(compare("2.11.0", "2.14.0") < 0);
        assertTrue(compare("33.6.0-jre", "33.7.2") < 0);
        assertTrue(compare("12.1.0.beta3", "12.1.0") < 0);
        assertEquals(0, compare("33.7.2-jre", "33.7.2"));
        assertTrue(compare("12.1.14", "12.1.10") > 0);
        Rule jetty = new Rule("org.eclipse.jetty", "jetty-server", List.of("12.1.10", "12.0.36", "11.0.31"), "x");
        assertTrue(vulnerable(jetty, "11.0.21"));
        assertTrue(vulnerable(jetty, "12.0.10"));
        assertFalse(vulnerable(jetty, "12.1.14"));
        assertTrue(vulnerable(jetty, "9.4.50"), "an older line none of the fixes names");
        assertFalse(vulnerable(new Rule("commons-io", "commons-io", List.of("2.14.0"), "x"), "2.22.0"));
        assertTrue(vulnerable(new Rule("c3p0", "c3p0", List.of("*"), "x"), "0.9.1.1"));
        assertFalse(vulnerable(jetty, "unknown"));
    }

    @Test
    void noEmbeddedJarShipsAKnownVulnerableComponentWithoutADatedWaiver() throws Exception {
        List<Rule> rules = rules();
        List<Waiver> waivers = waivers();
        Set<Waiver> used = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();
        for (String[] c : embedded()) {
            for (Rule r : rules) {
                if (!r.group().equals(c[1]) || !r.artifact().equals(c[2]) || !vulnerable(r, c[3])) continue;
                String coords = c[1] + ":" + c[2] + "@" + c[3];
                Waiver w = waivers.stream().filter(x -> x.coordinates().equals(coords) && x.jar().equals(c[0])).findFirst().orElse(null);
                if (w == null) findings.add(c[0] + " ships " + coords + " (" + r.ids() + ")");
                else used.add(w);
            }
        }
        assertEquals(List.of(), findings, "known-vulnerable components without a waiver");
        LocalDate today = LocalDate.now();
        for (Waiver w : waivers) {
            assertFalse(today.isAfter(w.until()), "waiver expired on " + w.until() + ": " + w.coordinates() + " in " + w.jar() + " (" + w.reason() + ")");
            assertTrue(used.contains(w), "waiver matches nothing any more, remove it: " + w.coordinates() + " in " + w.jar());
        }
    }
}
