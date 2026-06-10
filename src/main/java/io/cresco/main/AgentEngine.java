package io.cresco.main;

import java.io.File;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AgentEngine
{

    private static final Logger logger = Logger.getLogger(AgentEngine.class.getName());

    protected static HostApplication ha;
    public static HostApplication create() throws Exception {
        ha = new HostApplication();
        return ha;
    }
    public static HostApplication getHa() {
        return ha;
    }

    public static void main(String[] argv) {

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
            logger.info("Starting Cresco AgentEngine...");
            AgentEngine.create();

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "AgentEngine failed to start", ex);
        }

    }
}