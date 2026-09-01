package com.rag2agent.mcp.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.mcp.server")
public class McpServerProperties {

    private String token = "dev-mcp-token";
    private List<String> requiredScopes = new ArrayList<>(List.of("mcp:read"));
    private List<String> tokenScopes = new ArrayList<>(List.of("mcp:read"));

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getRequiredScopes() {
        return requiredScopes;
    }

    public void setRequiredScopes(List<String> requiredScopes) {
        this.requiredScopes = requiredScopes;
    }

    public List<String> getTokenScopes() {
        return tokenScopes;
    }

    public void setTokenScopes(List<String> tokenScopes) {
        this.tokenScopes = tokenScopes;
    }
}
