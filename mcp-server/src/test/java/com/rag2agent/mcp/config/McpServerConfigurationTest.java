package com.rag2agent.mcp.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class McpServerConfigurationTest {

    @Test
    void productionRejectsDefaultDevelopmentToken() {
        McpServerProperties.Client client = new McpServerProperties.Client();
        client.setName("local");
        client.setToken("dev-mcp-token");
        client.setScopes(List.of("mcp:read"));
        McpServerProperties properties = new McpServerProperties();
        properties.setClients(List.of(client));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class,
                () -> McpServerConfiguration.validateCredentials(properties, environment));
    }

    @Test
    void enabledServerRequiresAtLeastOneCredential() {
        MockEnvironment environment = new MockEnvironment();

        assertThrows(IllegalStateException.class,
                () -> McpServerConfiguration.validateCredentials(
                        new McpServerProperties(), environment));
    }
}
