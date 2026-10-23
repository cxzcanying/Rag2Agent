package com.rag2agent.mcp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.rag2agent.mcp.config.McpServerProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpAuthorizationFilterTest {

    @Test
    void requestHeaderCannotElevateReadOnlyClient() throws Exception {
        McpAuthorizationFilter filter = filter(List.of("mcp:write"));
        MockHttpServletRequest request = request("reader-token");
        request.addHeader("X-MCP-Scopes", "mcp:read,mcp:write");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void serverCredentialResolvesCallerWithoutScopeHeader() throws Exception {
        McpAuthorizationFilter filter = filter(List.of("mcp:read"));
        MockHttpServletRequest request = request("reader-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("reader", request.getAttribute(McpAuthorizationFilter.CALLER_ATTRIBUTE));
        assertSame(request, chain.getRequest());
    }

    private McpAuthorizationFilter filter(List<String> requiredScopes) {
        McpServerProperties.Client client = new McpServerProperties.Client();
        client.setName("reader");
        client.setToken("reader-token");
        client.setScopes(List.of("mcp:read"));
        McpServerProperties properties = new McpServerProperties();
        properties.setRequiredScopes(requiredScopes);
        properties.setClients(List.of(client));
        return new McpAuthorizationFilter(properties);
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
