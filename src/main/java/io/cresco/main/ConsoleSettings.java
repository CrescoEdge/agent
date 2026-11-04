package io.cresco.main;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether the Felix WebConsole may start, and the framework properties that secure it.
 *
 * <p>The console ({@code enable_console=true}) used to run with the WebConsole's built-in {@code admin}/{@code admin}
 * login, over plain HTTP, on every interface. Now it is enabled only when it can be secured, else it stays off and the
 * agent logs why (fail closed; the agent itself still starts):
 * <ul>
 *   <li><b>Credentials are required.</b> {@code console_username} and a password from {@code console_password_file}
 *       (an owner-only file, mode 0600/0400, preferred) or {@code console_password}. The password needs at least
 *       {@value #MIN_PASSWORD_CHARS} characters and may not be a well-known default or the user name. The WebConsole
 *       receives only a salted, iterated SHA-384 hash of it.</li>
 *   <li><b>Loopback by default.</b> It binds {@code console_host} (default {@code 127.0.0.1}) on {@code console_port}
 *       (default the agent's {@code port}, else 8080).</li>
 *   <li><b>TLS off loopback.</b> With {@code console_keystore} (PKCS12) and {@code console_keystore_password}
 *       ({@code _file}) it serves HTTPS only, on {@code console_https_port} (default 8443). Bound to any other address
 *       without a keystore it is refused: the password would cross the network in clear.</li>
 * </ul>
 */
final class ConsoleSettings {

    static final int MIN_PASSWORD_CHARS = 12;
    static final int HASH_ITERATIONS = 20_000;
    private static final Set<String> WEAK = Set.of("admin", "password", "changeit", "cresco", "administrator", "webconsole",
            "123456789012", "passwordpassword", "adminadminadmin");

    /** The outcome. */
    static final class Decision {
        final boolean enabled;
        /** Why it stays off (enable_console=true but it could not be secured), or null. */
        final String refusal;
        /** Framework properties to set when enabled (never the clear password). */
        final Map<String, String> frameworkProperties;
        /** One line for the log (no secrets). */
        final String summary;

        private Decision(boolean enabled, String refusal, Map<String, String> props, String summary) {
            this.enabled = enabled;
            this.refusal = refusal;
            this.frameworkProperties = props;
            this.summary = summary;
        }

        static Decision off() { return new Decision(false, null, Map.of(), "console disabled"); }

        static Decision refused(String why) { return new Decision(false, why, Map.of(), "console NOT started: " + why); }
    }

    /** Reads a config value (-D, CRESCO_ env, agent.ini, as {@link Config#getStringParam} does). */
    interface Source {
        String get(String key);
    }

    private ConsoleSettings() {}

    static Decision resolve(Source cfg, String defaultHttpPort) {
        String enable = cfg.get("enable_console");
        if (enable == null || !(enable.trim().equalsIgnoreCase("true") || enable.trim().equals("1"))) return Decision.off();

        String user = trim(cfg.get("console_username"));
        if (user == null) return Decision.refused("enable_console=true needs console_username and console_password_file "
                + "(or console_password); the WebConsole's built-in admin/admin login is never used");
        if (!user.matches("[A-Za-z0-9._@-]{1,64}")) return Decision.refused("console_username must be 1-64 of A-Z a-z 0-9 . _ @ -");

        String password;
        String pwFile = trim(cfg.get("console_password_file"));
        try {
            password = pwFile != null ? readOwnerOnly(Paths.get(pwFile), "console_password_file") : cfg.get("console_password");
        } catch (IllegalStateException e) {
            return Decision.refused(e.getMessage());
        }
        if (password == null || password.isEmpty()) {
            return Decision.refused("enable_console=true needs console_password_file (or console_password)");
        }
        String weak = weakness(user, password);
        if (weak != null) return Decision.refused("console_password " + weak);

        String host = trim(cfg.get("console_host"));
        if (host == null) host = "127.0.0.1";
        boolean loopback = isLoopback(host);
        String port = trim(cfg.get("console_port"));
        if (port == null) port = defaultHttpPort;
        if (!validPort(port)) return Decision.refused("console_port " + port + " is not a port number");

        Map<String, String> p = new LinkedHashMap<>();
        p.put("org.apache.felix.http.host", host);
        String keystore = trim(cfg.get("console_keystore"));
        String scheme;
        String where;
        if (keystore != null) {
            String ksPassword;
            String ksPwFile = trim(cfg.get("console_keystore_password_file"));
            try {
                ksPassword = ksPwFile != null ? readOwnerOnly(Paths.get(ksPwFile), "console_keystore_password_file") : cfg.get("console_keystore_password");
            } catch (IllegalStateException e) {
                return Decision.refused(e.getMessage());
            }
            if (!Files.isRegularFile(Paths.get(keystore))) return Decision.refused("console_keystore " + keystore + " does not exist");
            if (ksPassword == null || ksPassword.isEmpty()) return Decision.refused("console_keystore needs console_keystore_password_file (or console_keystore_password)");
            String httpsPort = trim(cfg.get("console_https_port"));
            if (httpsPort == null) httpsPort = "8443";
            if (!validPort(httpsPort)) return Decision.refused("console_https_port " + httpsPort + " is not a port number");
            p.put("org.apache.felix.http.enable", "false");
            p.put("org.apache.felix.https.enable", "true");
            p.put("org.osgi.service.http.port.secure", httpsPort);
            p.put("org.apache.felix.https.keystore", Paths.get(keystore).toAbsolutePath().toString());
            p.put("org.apache.felix.https.keystore.password", ksPassword);
            p.put("org.apache.felix.https.keystore.key.password", ksPassword);
            p.put("org.apache.felix.https.keystore.type", "PKCS12");
            p.put("org.apache.felix.https.jetty.protocols.included", "TLSv1.3,TLSv1.2");
            scheme = "https";
            where = host + ":" + httpsPort;
        } else {
            if (!loopback) {
                return Decision.refused("console_host " + host + " is not a loopback address and no console_keystore is set: the console "
                        + "password would cross the network in clear; set console_keystore (PKCS12) or bind 127.0.0.1");
            }
            p.put("org.apache.felix.http.enable", "true");
            p.put("org.apache.felix.https.enable", "false");
            p.put("org.osgi.service.http.port", port);
            scheme = "http";
            where = host + ":" + port;
        }
        p.put("felix.webconsole.username", user);
        p.put("felix.webconsole.password", webConsoleHash(password, new SecureRandom(), HASH_ITERATIONS));
        return new Decision(true, null, p, "console enabled on " + scheme + "://" + where + "/system/console for user " + user);
    }

    /** Why a password is too weak, or null. */
    static String weakness(String user, String password) {
        if (password.length() < MIN_PASSWORD_CHARS) return "needs at least " + MIN_PASSWORD_CHARS + " characters";
        String lower = password.toLowerCase(Locale.ROOT);
        if (WEAK.contains(lower)) return "is a well-known default";
        if (lower.equals(user.toLowerCase(Locale.ROOT))) return "may not be the user name";
        if (password.chars().distinct().count() < 4) return "is too repetitive";
        return null;
    }

    /**
     * The WebConsole's own hashed-password form ({@code org.apache.felix.webconsole.internal.servlet.Password}):
     * {@code {sha-384}<salt>-<iterations>-<base64>}, where the hash is SHA-384 applied {@code iterations} times to the
     * UTF-8 bytes of salt + password. The console compares a login against it; the clear password is never handed over.
     */
    static String webConsoleHash(String password, SecureRandom random, int iterations) {
        byte[] s = new byte[16];
        random.nextBytes(s);
        StringBuilder salt = new StringBuilder();
        for (byte b : s) salt.append(String.format("%02x", b & 0xff));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-384");
            byte[] h = (salt + password).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < iterations; i++) {
                md.reset();
                h = md.digest(h);
            }
            return "{sha-384}" + salt + "-" + iterations + "-" + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-384 unavailable", e);
        }
    }

    static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) return true;
        try {
            if (host.matches("[0-9.]+") || host.contains(":")) return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignore) {
            // not an address
        }
        return false;
    }

    private static boolean validPort(String p) {
        try {
            int n = Integer.parseInt(p.trim());
            return n > 0 && n < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * A secret from a file only the agent's user can read or replace: a regular file (not a symlink) owned by the JVM
     * user, mode 0600 or 0400, in a directory owned by that user or root and not group- or world-writable (the rules
     * of the library's OwnerOnlyFile, which the launcher cannot load). Surrounding whitespace is stripped.
     *
     * @throws IllegalStateException naming the file and the problem, never the content
     */
    static String readOwnerOnly(Path file, String label) {
        Path p = file.toAbsolutePath().normalize();
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException(label + " " + p + " does not exist");
        if (Files.isSymbolicLink(p)) throw new IllegalStateException(label + " " + p + " is a symbolic link; point it at the file itself");
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException(label + " " + p + " is not a regular file");
        String me = System.getProperty("user.name");
        try {
            PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (me == null || !me.equals(a.owner().getName())) {
                throw new IllegalStateException(label + " " + p + " is owned by " + a.owner().getName() + ", not by the agent's user " + me);
            }
            Set<PosixFilePermission> perms = a.permissions();
            if (!OWNER_ONLY.containsAll(perms) || !perms.contains(PosixFilePermission.OWNER_READ)) {
                throw new IllegalStateException(label + " " + p + " has mode " + PosixFilePermissions.toString(perms) + "; it must be 0600 or 0400");
            }
            Path dir = p.getParent().toRealPath();
            PosixFileAttributes d = Files.readAttributes(dir, PosixFileAttributes.class);
            if (!me.equals(d.owner().getName()) && !"root".equals(d.owner().getName())) {
                throw new IllegalStateException(label + " directory " + dir + " is owned by " + d.owner().getName() + "; it must be owned by " + me + " or root");
            }
            if (d.permissions().contains(PosixFilePermission.GROUP_WRITE) || d.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalStateException(label + " directory " + dir + " is group- or world-writable; the file could be replaced");
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).strip();
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException(label + " " + p + ": this filesystem has no POSIX owner/mode, so the file cannot be checked");
        } catch (IOException e) {
            throw new IllegalStateException(label + " " + p + ": " + e.getMessage());
        }
    }
}
