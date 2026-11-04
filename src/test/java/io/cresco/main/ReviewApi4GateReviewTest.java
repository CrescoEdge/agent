package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial review, round 4 (review/api-20261104): a waiver (sbom/waivers.txt) or an unknown-version allowance
 * (sbom/unknown-version-allowlist.txt) needs a "reviewer=<name>", but the name is free text that nothing checks: whoever
 * commits a vulnerable component can approve its own waiver, and renew it every 30 days. The current allowlist entries
 * name the author's own agent as their reviewer. Nothing in the repository makes a second person review changes to
 * sbom/ (no CODEOWNERS, no signed approvals), and the allowlist entries never expire.
 */
class ReviewApi4GateReviewTest {

    @Test
    void changesToTheGatesExceptionsNeedAnEnforcedReview() throws Exception {
        List<Path> owners = List.of(Paths.get("CODEOWNERS"), Paths.get(".github", "CODEOWNERS"), Paths.get("docs", "CODEOWNERS"));
        String text = null;
        for (Path p : owners) if (Files.exists(p)) text = Files.readString(p);
        assertNotNull(text, "no CODEOWNERS: a waiver's reviewer is whatever name the committer writes");
        assertTrue(text.lines().anyMatch(l -> l.strip().startsWith("/sbom/") || l.strip().startsWith("sbom/")),
                "CODEOWNERS does not cover sbom/");
    }
}
