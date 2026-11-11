package io.cresco.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * What this agent jar is: {@code java -jar agent.jar --version}. The release ({@code Cresco-Release}: 1.3.&lt;n&gt;, or
 * "unreleased" for any other build), the build ({@code Implementation-Version}: version, git revision, build time) and
 * every embedded Cresco bundle as {@code bundles.lock} names it (file, symbolic name, bundle version, SHA-256, where it
 * came from). An operator, or a script, can tell from the jar alone which build of which components a node runs.
 */
final class AgentVersion {

    static final String LOCK_RESOURCE = "/META-INF/cresco/bundles.lock";

    private AgentVersion() {
    }

    /** The text {@code --version} prints. */
    static String describe() {
        String release = null, build = null;
        try {
            File self = new File(AgentVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (self.isFile()) {
                try (JarFile jar = new JarFile(self, false)) {
                    Manifest mf = jar.getManifest();
                    if (mf != null) {
                        Attributes a = mf.getMainAttributes();
                        release = a.getValue("Cresco-Release");
                        build = a.getValue("Implementation-Version");
                    }
                }
            }
        } catch (Exception notAJar) {
            // run from a class directory: no manifest to read
        }
        List<String> lock = new ArrayList<>();
        try (InputStream in = AgentVersion.class.getResourceAsStream(LOCK_RESOURCE)) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) lock.add(line);
            }
        } catch (IOException ignore) {
            // reported below as "no bundles.lock"
        }
        return format(release, build, lock);
    }

    /** {@code lock}: the lines of bundles.lock (comments and empty lines are skipped). */
    static String format(String release, String build, List<String> lock) {
        StringBuilder sb = new StringBuilder();
        boolean released = release != null && !release.isEmpty() && !"unreleased".equals(release);
        sb.append("Cresco agent ").append(released ? release : "(not a release build)");
        sb.append(build == null || build.isEmpty() ? "" : ", build " + build).append('\n');
        int n = 0;
        for (String line : lock) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length != 5) continue;
            if (n++ == 0) sb.append("embedded Cresco bundles (bundles.lock):\n");
            sb.append(String.format("  %-16s %-22s %-32s sha256:%s  %s%n", f[0], f[1], f[2], f[3], f[4]));
        }
        if (n == 0) sb.append("no bundles.lock in this jar: the embedded bundles are not pinned\n");
        return sb.toString();
    }
}
