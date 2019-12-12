package io.cresco.main;


import java.io.File;

public class AgentEngine
{

    protected static HostApplication ha;


    public static HostApplication create() throws Exception {
        ha = new HostApplication();
        return ha;
    }

    public static HostApplication getHa() {
        return ha;
    }

    public static void main(String[] argv) {

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
            ex.printStackTrace();
        }

    }
}