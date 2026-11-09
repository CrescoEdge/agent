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

        // the FIPS crypto modules (unmodified jars in crypto/) on the class path before anything else (CryptoBoot); which of
        // them serves the JVM's crypto is set once the agent's configuration is read (HostApplication)
        try {
            CryptoBoot.install();
        } catch (CryptoBoot.Refused r) {
            LOG.severe("agent NOT started: " + r.getMessage());
            System.exit(1);
        }
        if (argv.length > 0 && argv[0].equals("--crypto")) {
            try {
                System.exit(CryptoTool.run(argv));
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.SEVERE, "crypto tool failed", e);
                System.exit(1);
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