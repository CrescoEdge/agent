package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 3 (review/api-20261104): the known-vulnerable-component gate is a unit test
 * (EmbeddedKnownVulnerabilitiesTest), so a build that skips tests skips the gate. The workflow that builds the release
 * asset does exactly that ({@code mvn package -Dmaven.test.skip=true}), as do the DGX snapshot and publish scripts
 * (-DskipTests). Reproduced: with the c3p0 waiver removed, {@code mvn -o package -Dmaven.test.skip=true} exits 0 and the
 * agent jar ships library.jar with c3p0-0.9.1.1; the same build with the gate test fails.
 */
class ReviewApi3GateTest {

    @Test
    void theReleaseBuildRunsTheKnownVulnerabilityGate() throws Exception {
        Path wf = Paths.get(".github", "workflows", "main.yaml");
        assertTrue(Files.exists(wf), wf + " (the release build)");
        String text = Files.readString(wf);
        assertFalse(text.contains("maven.test.skip=true") || text.contains("-DskipTests"),
                "the release build skips tests, and with them the known-vulnerable-component gate");
    }
}
