package com.rag2agent.mcp.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.mcp.server")
public class McpServerProperties {

    private boolean enabled;
    private String token = "dev-mcp-token";
    private List<String> requiredScopes = new ArrayList<>(List.of("mcp:read"));
    private List<String> tokenScopes = new ArrayList<>(List.of("mcp:read"));
    private String endpoint = "/mcp";
    private String serverName = "rag2agent-mcp";
    private String serverVersion = "2.0.0";
    private long keepAliveSeconds = 30;
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:*"));
    private List<String> allowedHosts = new ArrayList<>(List.of("localhost:*"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }
}
