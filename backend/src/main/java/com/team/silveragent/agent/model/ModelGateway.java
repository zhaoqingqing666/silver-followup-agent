package com.team.silveragent.agent.model;

public interface ModelGateway {
    String complete(ModelRequest request);
    boolean available();
    String providerName();
    String modelName();
}
