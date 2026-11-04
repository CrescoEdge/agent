package io.cresco.main;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** The bundles HostApplication installs by name are embedded, and only one Felix HTTP service ships. */
class EmbeddedConsoleBundlesTest {

    static Manifest manifest(String jar) throws Exception {
        try (InputStream in = EmbeddedConsoleBundlesTest.class.getClassLoader().getResourceAsStream(jar)) {
            assertNotNull(in, jar + " is not embedded (src/main/resources)");
            try (JarInputStream j = new JarInputStream(in)) {
                return j.getManifest();
            }
        }
    }

    @Test
    void everyNamedBundleIsEmbeddedAsAnOsgiBundle() throws Exception {
        String[][] named = {
                {HostApplication.SERVLET_API_JAR, "org.apache.felix.http.servlet-api"},
                {HostApplication.HTTP_JETTY_JAR, "org.apache.felix.http.jetty12"},
                {HostApplication.WEBCONSOLE_JAR, "org.apache.felix.webconsole"},
                {HostApplication.INVENTORY_JAR, "org.apache.felix.inventory"},
                {HostApplication.ENCODER_JAR, "org.owasp.encoder"}};
        for (String[] n : named) {
            String bsn = manifest(n[0]).getMainAttributes().getValue("Bundle-SymbolicName");
            assertNotNull(bsn, n[0]);
            assertEquals(n[1], bsn.split(";")[0].trim(), n[0]);
        }
    }

    @Test
    void oneFelixHttpServiceAndNoBundlesOnlyTheOldStackNeeded() throws Exception {
        File dir = new File(HostApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String[] jars = dir.list((d, name) -> name.endsWith(".jar"));
        assertNotNull(jars, dir + " lists no embedded jars");
        List<String> http = new ArrayList<>(), stale = new ArrayList<>();
        for (String j : jars) {
            if (j.startsWith("org.apache.felix.http.jetty") || j.startsWith("org.apache.felix.http.base")) http.add(j);
            if (j.startsWith("commons-fileupload-") || j.startsWith("commons-io-") || j.startsWith("org.osgi.service.useradmin-")
                    || j.startsWith("org.osgi.service.cm-")) stale.add(j);
        }
        assertEquals(List.of(HostApplication.HTTP_JETTY_JAR), http, "exactly one Felix HTTP service (Jetty 12), no separate http.base");
        assertEquals(List.of(), stale, "jars only the removed Felix HTTP base (or nothing) used");
    }
}
