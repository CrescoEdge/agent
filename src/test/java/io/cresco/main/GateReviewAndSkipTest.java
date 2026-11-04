package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The known-vulnerable-component gate cannot be skipped with a common flag, and changes to it and its exceptions need the
 * owner's review (CODEOWNERS; GitHub enforces it where branch protection requires code-owner reviews).
 */
class GateReviewAndSkipTest {

    /** The antrun executions of pom.xml by id, with their configuration. */
    static Map<String, Element> antrunExecutions() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document pom = f.newDocumentBuilder().parse(Paths.get("pom.xml").toFile());
        Map<String, Element> out = new HashMap<>();
        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element p = (Element) plugins.item(i);
            if (!"maven-antrun-plugin".equals(text(p, "artifactId"))) continue;
            NodeList ex = p.getElementsByTagName("execution");
            for (int j = 0; j < ex.getLength(); j++) {
                Element e = (Element) ex.item(j);
                out.put(text(e, "id"), (Element) e.getElementsByTagName("configuration").item(0));
            }
        }
        return out;
    }

    static String text(Element e, String child) {
        NodeList n = e.getElementsByTagName(child);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().strip();
    }

    @Test
    void theSbomAndTheGateCannotBeSkippedWithMavenAntrunSkip() throws Exception {
        Map<String, Element> ex = antrunExecutions();
        for (String id : List.of("sbom", "known-vulnerability-gate")) {
            Element cfg = ex.get(id);
            assertNotNull(cfg, "the " + id + " execution");
            // a parameter set in the POM is not taken from its user property: only a literal false cannot be skipped
            assertEquals("false", text(cfg, "skip"), id + " must set <skip>false</skip> literally (else -Dmaven.antrun.skip=true skips it)");
            Element java = (Element) cfg.getElementsByTagName("java").item(0);
            assertEquals("true", java.getAttribute("failonerror"), id + " fails the build when it fails");
        }
        assertEquals("${cresco.skipBundleCheck}", text(ex.get("verify-embedded-bundles"), "skip"),
                "the bundle check is skipped only by its documented flag");
    }

    @Test
    void theGateWritesTheMarkerTheAssemblyNeedsAndPutsInTheManifest() throws Exception {
        Element gate = antrunExecutions().get("known-vulnerability-gate");
        assertEquals("true", text(gate, "exportAntProperties"), "the gate's marker becomes Maven properties");
        String pom = Files.readString(Paths.get("pom.xml"));
        for (String arg : List.of("<arg value=\"${project.build.outputDirectory}\"/>", "<arg value=\"${project.build.directory}/gate\"/>",
                "<loadproperties srcFile=\"${project.build.directory}/gate/marker.properties\"/>",
                "<Cresco-Gate>${cresco.gate.marker}</Cresco-Gate>", "<Cresco-Gate-Files>${cresco.gate.files}</Cresco-Gate-Files>")) {
            assertTrue(pom.contains(arg), "pom.xml: " + arg);
        }
        String assembly = Files.readString(Paths.get("src", "assemble", "bin.xml"));
        assertTrue(assembly.contains("<source>${project.build.directory}/gate/META-INF/cresco/sbom.cdx.json</source>")
                && assembly.contains("<source>${project.build.directory}/gate/META-INF/cresco/embedded.sha384</source>"),
                "the assembly takes the gate's SBOM and list (and fails without them)");
        for (String script : List.of("sign-release.sh", "verify-release.sh")) {
            assertTrue(Files.readString(Paths.get("scripts", script)).contains("gate_check()"), script + " checks the gate marker");
        }
    }

    @Test
    void theOwnerReviewsTheGateItsExceptionsAndItsWiring() throws Exception {
        String owners = Files.readString(Paths.get("CODEOWNERS"));
        for (String path : List.of("/sbom/**", "/pom.xml", "/src/assemble/**", "/src/main/java/io/cresco/main/KnownVulnerabilityGate.java",
                "/src/main/java/io/cresco/main/EmbeddedSbom.java", "/src/main/java/io/cresco/main/MavenSbom.java", "/CODEOWNERS")) {
            assertTrue(owners.lines().map(String::strip).anyMatch(l -> l.startsWith(path + " ") && l.endsWith("@codybum")),
                    "CODEOWNERS: " + path + " @codybum");
        }
        String readme = Files.readString(Paths.get("README.md"));
        assertTrue(readme.contains("Require review from Code Owners"), "the README says what enforces CODEOWNERS");
    }
}
