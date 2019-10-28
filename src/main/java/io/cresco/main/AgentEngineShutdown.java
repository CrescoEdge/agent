package io.cresco.main;


public class AgentEngineShutdown
{

    public static void main(String[] argv) {

        AgentEngine.getHa().shutdownApplication();

    }
}