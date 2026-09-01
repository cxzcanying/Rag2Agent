package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.mcp.remote")
public class McpRemoteProperties {

    private boolean enabled;
    private String endpoint = "http://localhost:19090/mcp";
    private String token = "dev-mcp-token";
    private String scopes = "mcp:read";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
}
