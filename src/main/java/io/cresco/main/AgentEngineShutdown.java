package io.cresco.main;


public class AgentEngineShutdown
{

    public AgentEngineShutdown() {

    AgentEngine.getHa().shutdownApplication();

    }
}