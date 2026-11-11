package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code --version} says which release and build a jar is and which bundles it embeds, from the jar alone. */
class AgentVersionTest {

    @Test
    void aReleaseNamesItselfItsBuildAndEveryEmbeddedBundle() {
        String out = AgentVersion.format("1.3.374", "1.3-SNAPSHOT.49d3f.20261111025129", List.of(
                "# a comment", "",
                "controller.jar io.cresco.controller 1.3.0.SNAPSHOT-20261111023154 " + "a".repeat(64) + " central:io.cresco:controller:1.3-20261111.024544-122",
                "executor.jar io.cresco.executor 1.3.0.SNAPSHOT-20261109194741 " + "b".repeat(64) + " committed"));
        String[] lines = out.split("\n");
        assertEquals("Cresco agent 1.3.374, build 1.3-SNAPSHOT.49d3f.20261111025129", lines[0]);
        assertEquals(4, lines.length, out);
        assertTrue(lines[2].contains("controller.jar") && lines[2].contains("sha256:" + "a".repeat(64))
                && lines[2].endsWith("central:io.cresco:controller:1.3-20261111.024544-122"), lines[2]);
        assertTrue(lines[3].contains("executor.jar") && lines[3].endsWith("committed"), lines[3]);
    }

    @Test
    void aBuildThatIsNoReleaseSaysSoAndAJarWithoutALockSaysItsBundlesAreNotPinned() {
        assertTrue(AgentVersion.format("unreleased", "1.3-SNAPSHOT.abcde.20261111", List.of()).startsWith("Cresco agent (not a release build), build 1.3-SNAPSHOT.abcde"));
        assertTrue(AgentVersion.format(null, null, List.of()).startsWith("Cresco agent (not a release build)\n"));
        assertTrue(AgentVersion.format("1.3.1", "b", List.of("# only a comment")).contains("no bundles.lock in this jar"));
    }

    @Test
    void theDescriptionOfThisBuildDoesNotFailOutsideAJar() {
        assertTrue(AgentVersion.describe().startsWith("Cresco agent "));
    }
}
