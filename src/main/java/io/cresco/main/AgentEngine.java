package io.cresco.main;

import java.io.File;

public class AgentEngine
{
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AgentEngine.class.getName());

    protected static HostApplication ha;
    public static HostApplication create() throws Exception {
        ha = new HostApplication();
        return ha;
    }
    public static HostApplication getHa() {
        return ha;
    }

    public static void main(String[] argv) {

        // TLS acceleration for the ActiveMQ inter-node broker bridge (nio+ssl:32010) -- the real
        // cross-node data path. ActiveMQ uses JSSE (SSLContext.getInstance), not Netty, so tcnative
        // cannot reach it; installing Conscrypt (BoringSSL) as the top JCE provider transparently
        // makes every SSLContext in this JVM native, including the broker's. Opt-in via
        // -Dcresco_ssl_provider=CONSCRYPT; reflective + guarded so a missing/incompatible native
        // (e.g. an unsupported arch) falls back to the JDK provider instead of failing startup.
        String sslProvider = System.getProperty("cresco_ssl_provider", "JDK");
        if ("CONSCRYPT".equalsIgnoreCase(sslProvider)) {
            try {
                java.security.Provider p = (java.security.Provider)
                        Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance();
                java.security.Security.insertProviderAt(p, 1);
                LOG.info("Installed Conscrypt (BoringSSL) as top JCE provider -- broker nio+ssl TLS accelerated");
            } catch (Throwable t) {
                LOG.warning("cresco_ssl_provider=CONSCRYPT requested but unavailable (" + t
                        + "); falling back to JDK TLS");
            }
        }

        // suppress initial pax logging
        System.setProperty("org.ops4j.pax.logging.DefaultServiceLog.level", "WARN");

        // suppress logging
        System.setProperty("org.apache.commons.logging.Log",
                "org.apache.commons.logging.impl.NoOpLog");

        String configFile = null;
        if(argv.length > 1) {
            configFile =  argv[1];
        } else {
           configFile = "agent.ini";
        }

        File agentConfig = new File(configFile);
        if(agentConfig.isFile()) {
            System.setProperty("agentConfig", agentConfig.getAbsolutePath());
        }


        try {
            AgentEngine.create();

        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }

    }
}