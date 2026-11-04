package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The WebConsole is enabled only when secured: configured credentials, loopback by default, HTTPS off loopback. */
class ConsoleSettingsTest {

    static final String GOOD = "a-long-console-pw-9";

    static ConsoleSettings.Decision resolve(Map<String, String> cfg) {
        return ConsoleSettings.resolve(cfg::get, "8080");
    }

    static Map<String, String> enabled() {
        Map<String, String> m = new HashMap<>();
        m.put("enable_console", "true");
        m.put("console_username", "ops");
        m.put("console_password", GOOD);
        return m;
    }

    @Test
    void offUnlessAskedFor() {
        ConsoleSettings.Decision d = resolve(new HashMap<>());
        assertFalse(d.enabled);
        assertNull(d.refusal);
        assertTrue(d.frameworkProperties.isEmpty());
        assertFalse(resolve(Map.of("enable_console", "false")).enabled);
    }

    @Test
    void credentialsAreRequiredAndNeverTheDefaults() {
        Map<String, String> m = enabled();
        m.remove("console_username");
        assertNotNull(resolve(m).refusal);
        m = enabled();
        m.remove("console_password");
        assertNotNull(resolve(m).refusal);
        for (String weak : new String[]{"admin", "short-pw", "changeit", "adminadminadmin", "ops", "aaaaaaaaaaaaaa"}) {
            m = enabled();
            m.put("console_password", weak);
            ConsoleSettings.Decision d = resolve(m);
            assertFalse(d.enabled, weak);
            assertNotNull(d.refusal, weak);
            assertFalse(d.refusal.contains(weak) && weak.length() > 3, "the refusal never repeats the password");
        }
        m = enabled();
        m.put("console_username", "o ps");
        assertNotNull(resolve(m).refusal);
    }

    @Test
    void enabledBindsLoopbackOverHttpAndHandsTheConsoleOnlyAHash() {
        ConsoleSettings.Decision d = resolve(enabled());
        assertTrue(d.enabled, d.refusal);
        Map<String, String> p = d.frameworkProperties;
        assertEquals("127.0.0.1", p.get("org.apache.felix.http.host"));
        assertEquals("8080", p.get("org.osgi.service.http.port"));
        assertEquals("ops", p.get("felix.webconsole.username"));
        String hash = p.get("felix.webconsole.password");
        assertTrue(hash.startsWith("{sha-384}"), hash);
        assertFalse(p.values().stream().anyMatch(v -> v.contains(GOOD)), "the clear password is never a framework property");
        assertFalse(d.summary.contains(GOOD));
    }

    @Test
    void offLoopbackNeedsTls(@TempDir Path dir) throws Exception {
        Map<String, String> m = enabled();
        m.put("console_host", "0.0.0.0");
        ConsoleSettings.Decision d = resolve(m);
        assertFalse(d.enabled);
        assertTrue(d.refusal.contains("in clear"), d.refusal);

        Path ks = Files.createFile(dir.resolve("console.p12"));
        m.put("console_keystore", ks.toString());
        assertNotNull(resolve(m).refusal, "a keystore needs its password");
        m.put("console_keystore_password", "ks-password-123");
        d = resolve(m);
        assertTrue(d.enabled, d.refusal);
        assertEquals("false", d.frameworkProperties.get("org.apache.felix.http.enable"), "plain HTTP off");
        assertEquals("true", d.frameworkProperties.get("org.apache.felix.https.enable"));
        assertEquals("8443", d.frameworkProperties.get("org.osgi.service.http.port.secure"));
        assertEquals("PKCS12", d.frameworkProperties.get("org.apache.felix.https.keystore.type"));
        assertEquals("0.0.0.0", d.frameworkProperties.get("org.apache.felix.http.host"));
        assertTrue(d.summary.startsWith("console enabled on https://"));
        for (String host : new String[]{"127.0.0.1", "localhost", "::1", "127.0.0.5"}) assertTrue(ConsoleSettings.isLoopback(host), host);
        for (String host : new String[]{"10.0.0.1", "0.0.0.0", "agent.example.org", "::"}) assertFalse(ConsoleSettings.isLoopback(host), host);
    }

    @Test
    void aPasswordFileMustBeOwnerOnly(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("console.pw");
        Files.writeString(f, GOOD + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        Map<String, String> m = enabled();
        m.remove("console_password");
        m.put("console_password_file", f.toString());
        assertTrue(resolve(m).enabled, resolve(m).refusal);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        ConsoleSettings.Decision d = resolve(m);
        assertFalse(d.enabled);
        assertTrue(d.refusal.contains("0600"), d.refusal);
        assertFalse(d.refusal.contains(GOOD));
        Path link = dir.resolve("link.pw");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(link, f);
        m.put("console_password_file", link.toString());
        assertTrue(resolve(m).refusal.contains("symbolic link"));
    }

    /** The hash must be one the embedded WebConsole accepts: check it with the console's own Password class. */
    @Test
    void theEmbeddedWebConsoleAcceptsTheHashAndOnlyForThatPassword(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("webconsole.jar");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("org.apache.felix.webconsole-5.0.18.jar")) {
            assertNotNull(in, "embedded webconsole jar");
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Class<?> pw = Class.forName("org.apache.felix.webconsole.internal.servlet.Password", true, cl);
            Constructor<?> ctor = pw.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Method matches = pw.getDeclaredMethod("matches", byte[].class);
            matches.setAccessible(true);
            String hash = ConsoleSettings.webConsoleHash(GOOD, new SecureRandom(), ConsoleSettings.HASH_ITERATIONS);
            Object p = ctor.newInstance(hash);
            assertTrue((Boolean) matches.invoke(p, (Object) GOOD.getBytes(StandardCharsets.UTF_8)));
            assertFalse((Boolean) matches.invoke(p, (Object) "admin".getBytes(StandardCharsets.UTF_8)));
            assertFalse((Boolean) matches.invoke(p, (Object) (GOOD + "x").getBytes(StandardCharsets.UTF_8)));
            Method hashed = pw.getDeclaredMethod("isPasswordHashed", String.class);
            hashed.setAccessible(true);
            assertTrue((Boolean) hashed.invoke(null, hash));
        }
    }
}
