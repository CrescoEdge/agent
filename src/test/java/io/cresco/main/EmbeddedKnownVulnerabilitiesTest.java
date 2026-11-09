package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The known-vulnerable-component gate (KnownVulnerabilityGate, a package-phase build step): its rules, and the
 * repository's own lists against the embedded jars.
 */
class EmbeddedKnownVulnerabilitiesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    static KnownVulnerabilityGate.Component comp(String jar, String group, String name, String version) {
        return new KnownVulnerabilityGate.Component(jar, group, name, version);
    }

    static List<String> check(List<KnownVulnerabilityGate.Component> comps, String waiver, String allow) {
        List<String> problems = new ArrayList<>();
        List<KnownVulnerabilityGate.Rule> rules = KnownVulnerabilityGate.rules(List.of("commons-io:commons-io 2.14.0 CVE-2024-47554"), problems);
        List<KnownVulnerabilityGate.Waiver> w = KnownVulnerabilityGate.waivers(waiver == null ? List.of() : List.of(waiver), problems);
        List<KnownVulnerabilityGate.Allowed> a = KnownVulnerabilityGate.allowed(allow == null ? List.of() : List.of(allow), problems);
        problems.addAll(KnownVulnerabilityGate.check(comps, rules, w, a, TODAY));
        return problems;
    }

    @Test
    void versionsCompareLikeMaven() {
        assertTrue(KnownVulnerabilityGate.compare("2.11.0", "2.14.0") < 0);
        assertTrue(KnownVulnerabilityGate.compare("33.6.0-jre", "33.7.2") < 0);
        assertTrue(KnownVulnerabilityGate.compare("12.1.0.beta3", "12.1.0") < 0);
        assertEquals(0, KnownVulnerabilityGate.compare("33.7.2-jre", "33.7.2"));
        assertTrue(KnownVulnerabilityGate.compare("12.1.14", "12.1.10") > 0);
        KnownVulnerabilityGate.Rule jetty = new KnownVulnerabilityGate.Rule("org.eclipse.jetty", "jetty-server",
                List.of("12.1.10", "12.0.36", "11.0.31"), "x");
        assertTrue(KnownVulnerabilityGate.vulnerable(jetty, "11.0.21"));
        assertTrue(KnownVulnerabilityGate.vulnerable(jetty, "12.0.10"));
        assertFalse(KnownVulnerabilityGate.vulnerable(jetty, "12.1.14"));
        assertTrue(KnownVulnerabilityGate.vulnerable(jetty, "9.4.50"), "an older line none of the fixes names");
        assertTrue(KnownVulnerabilityGate.vulnerable(new KnownVulnerabilityGate.Rule("c3p0", "c3p0", List.of("*"), "x"), "0.9.1.1"));
    }

    @Test
    void aKnownVulnerableComponentNeedsAValidDatedReviewedWaiver() {
        List<KnownVulnerabilityGate.Component> c = List.of(comp("felix-http.jar", "commons-io", "commons-io", "2.11.0"));
        assertTrue(check(c, null, null).get(0).contains("felix-http.jar ships commons-io:commons-io@2.11.0 (CVE-2024-47554)"));
        assertEquals(List.of(), check(c, "commons-io:commons-io@2.11.0 felix-http.jar since=2026-11-04 until=2026-12-04 reviewer=alice "
                + "replaced at the next re-embed of that bundle", null));
        String longWaiver = "commons-io:commons-io@2.11.0 felix-http.jar since=2026-11-04 until=2027-01-04 reviewer=alice a reason long enough";
        assertTrue(check(c, longWaiver, null).stream().anyMatch(p -> p.contains("at most 30 days")), "a waiver lasts at most 30 days");
        assertTrue(check(c, "commons-io:commons-io@2.11.0 felix-http.jar since=2026-11-04 until=2026-11-20 reviewer= x", null)
                .stream().anyMatch(p -> p.contains("waiver refused")), "a reviewer is required");
        assertTrue(check(c, "commons-io:commons-io@2.11.0 felix-http.jar since=2026-11-04 until=2026-11-20 reviewer=alice short", null)
                .stream().anyMatch(p -> p.contains("no reason")), "a reason is required");
        assertTrue(check(c, "commons-io:commons-io@2.11.0 felix-http.jar since=2026-10-01 until=2026-10-30 reviewer=alice an old waiver past its end", null)
                .stream().anyMatch(p -> p.contains("expired")));
        assertTrue(check(c, "commons-io:commons-io@2.11.0 felix-http.jar since=2026-12-01 until=2026-12-20 reviewer=alice dated after today on purpose", null)
                .stream().anyMatch(p -> p.contains("future")));
        assertTrue(check(List.of(comp("felix-http.jar", "commons-io", "commons-io", "2.22.0")),
                "commons-io:commons-io@2.11.0 felix-http.jar since=2026-11-04 until=2026-12-04 reviewer=alice the old copy is gone now", null)
                .stream().anyMatch(p -> p.contains("matches nothing")), "an unused waiver fails");
    }

    @Test
    void aComponentOfUnknownVersionNeedsAReviewedAllowance() {
        List<KnownVulnerabilityGate.Component> c = List.of(comp("sysinfo.jar", "com.github.oshi", "oshi-core", "unknown"),
                comp("library.jar", null, "org.example.thing", null));
        List<String> p = check(c, null, null);
        assertEquals(2, p.size(), p.toString());
        assertTrue(p.get(0).contains("unknown version"));
        String dated = "sysinfo.jar com.github.oshi:oshi-core since=2026-11-04 until=2026-12-04 reviewer=alice inlined beside its nested jar, reviewed";
        List<String> one = check(c, null, dated);
        assertEquals(1, one.size(), one.toString());
        assertTrue(one.get(0).contains("org.example.thing"));
        assertTrue(check(List.of(), null, dated).stream().anyMatch(x -> x.contains("matches nothing")));
        assertTrue(check(List.of(), null, "sysinfo.jar com.github.oshi:oshi-core no reviewer here at all")
                .stream().anyMatch(x -> x.contains("allowance refused")));
    }

    @Test
    void anUnknownVersionAllowanceExpiresLikeAWaiver() {
        List<KnownVulnerabilityGate.Component> c = List.of(comp("sysinfo.jar", "com.github.oshi", "oshi-core", "unknown"));
        List<String> undated = check(c, null, "sysinfo.jar com.github.oshi:oshi-core reviewer=alice inlined beside its nested jar, reviewed");
        assertTrue(undated.stream().anyMatch(x -> x.contains("allowance refused") && x.contains("expires within 30 days")),
                "an allowance without an expiry is refused: " + undated);
        assertTrue(undated.stream().anyMatch(x -> x.contains("unknown version")), "and the component is then not allowed");
        assertTrue(check(c, null, "sysinfo.jar com.github.oshi:oshi-core since=2026-11-04 until=2027-01-04 reviewer=alice a reason long enough")
                .stream().anyMatch(x -> x.contains("at most 30 days")), "at most 30 days");
        assertTrue(check(c, null, "sysinfo.jar com.github.oshi:oshi-core since=2026-10-01 until=2026-10-30 reviewer=alice an old allowance past its end")
                .stream().anyMatch(x -> x.contains("allowance expired on 2026-10-30")));
        assertTrue(check(c, null, "sysinfo.jar com.github.oshi:oshi-core since=2026-12-01 until=2026-12-20 reviewer=alice dated after today on purpose")
                .stream().anyMatch(x -> x.contains("allowance dated in the future")));
        assertTrue(check(c, null, "sysinfo.jar com.github.oshi:oshi-core since=2026-11-04 until=2026-12-04 reviewer= x")
                .stream().anyMatch(x -> x.contains("allowance refused (no reviewer)")));
        assertEquals(List.of(), check(c, null, "sysinfo.jar com.github.oshi:oshi-core since=2026-11-04 until=2026-12-04 reviewer=alice "
                + "inlined beside its nested jar, reviewed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRepositoryListsAreWellFormedAndTheEmbeddedJarsPassThem() throws Exception {
        List<String> problems = new ArrayList<>();
        List<KnownVulnerabilityGate.Rule> rules = KnownVulnerabilityGate.rules(KnownVulnerabilityGate.read(Paths.get("sbom", "known-vulnerable-components.txt")), problems);
        List<KnownVulnerabilityGate.Waiver> waivers = KnownVulnerabilityGate.waivers(KnownVulnerabilityGate.read(Paths.get("sbom", "waivers.txt")), problems);
        List<KnownVulnerabilityGate.Allowed> allowed = KnownVulnerabilityGate.allowed(KnownVulnerabilityGate.read(Paths.get("sbom", "unknown-version-allowlist.txt")), problems);
        assertEquals(List.of(), problems, "the lists in sbom/ parse");
        File dir = new File(HostApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        assertNotNull(jars);
        // the reference jars the build resolves (pom.xml sbom-reference), as the SBOM step reads them
        java.nio.file.Path refDir = Paths.get("target", "sbom-reference");
        assertTrue(java.nio.file.Files.isDirectory(refDir), "the build resolves the SBOM's reference jars before the tests");
        InlinedLibraries.Reference ref = InlinedLibraries.Reference.load(refDir);
        List<KnownVulnerabilityGate.Component> comps = new ArrayList<>();
        for (File j : jars) {
            Map<String, Object> c = EmbeddedSbom.component(j, ref);
            KnownVulnerabilityGate.walk(List.of((Object) c), null, comps);
        }
        assertEquals(List.of(), KnownVulnerabilityGate.check(comps, rules, waivers, allowed, LocalDate.now(java.time.ZoneOffset.UTC)));
        assertEquals(List.of(), allowed, "no component of unknown version is allowed: every version is known");
        for (KnownVulnerabilityGate.Component c : comps) assertNotEquals("unknown", c.version, c.jar + " " + c.coordinates());
    }
}
