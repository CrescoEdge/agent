package io.cresco.main;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Run in a JVM of its own (FipsModeTest): sets FIPS mode up as the agent does and reports, one line each, what a caller
 * sees. Args: [--bcjsse] [keystore-to-load password-file].
 */
public final class FipsProbe {
    public static void main(String[] args) throws Exception {
        CryptoBoot.install();
        String exports = CryptoBoot.systemPackages();
        System.out.println("exports=" + exports.split(",").length + (exports.contains("org.bouncycastle.cert;version=") ? " cert" : "")
                + (exports.contains("org.bouncycastle.jsse.provider;version=") ? " jsse" : "") + (exports.contains("uses:=") ? " uses" : ""));
        CryptoBoot.Settings s = new CryptoBoot.Settings();
        s.securityProfile = "production";
        int i = 0;
        if (args.length > 0 && args[0].equals("--bcjsse")) {
            s.fipsTls = "bcjsse";
            i = 1;
        }
        CryptoBoot.Status st = CryptoBoot.configure(s);
        System.out.println("fips=" + st.fips);
        System.out.println("providers=" + String.join(",", st.providers));
        try {
            MessageDigest.getInstance("MD5");
            System.out.println("md5=available");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("md5=refused");
        }
        System.out.println("sha256=" + MessageDigest.getInstance("SHA-256").getProvider().getName());
        System.out.println("tls=" + javax.net.ssl.SSLContext.getInstance("TLS").getProvider().getName());
        System.out.println("approvedOnly=" + System.getProperty("org.bouncycastle.fips.approved_only"));
        System.out.println("fipsTls=" + System.getProperty("cresco.crypto.fips.tls"));
        if (args.length >= i + 2) {
            char[] pw = Files.readAllLines(Paths.get(args[i + 1])).get(0).trim().toCharArray();
            try (InputStream in = Files.newInputStream(Paths.get(args[i]))) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(in, pw);
                String alias = ks.aliases().nextElement();
                System.out.println("keystore=loaded " + ks.getProvider().getName() + " key=" + (ks.getKey(alias, pw) != null));
            } catch (Exception e) {
                System.out.println("keystore=refused " + e.getMessage());
            }
        }
    }
}
