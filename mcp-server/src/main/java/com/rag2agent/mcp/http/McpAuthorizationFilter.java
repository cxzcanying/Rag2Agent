package com.rag2agent.mcp.http;

import com.rag2agent.mcp.config.McpServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.filter.OncePerRequestFilter;

/** MCP 传输层的 Bearer 与 scope 校验；SDK 负责协议，身份边界仍由应用控制。 */
public class McpAuthorizationFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "rag2agent.mcp.caller";
    public static final String SCOPES_ATTRIBUTE = "rag2agent.mcp.scopes";

    private final McpServerProperties properties;

    public McpAuthorizationFilter(McpServerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = bearerToken(request.getHeader("Authorization"));
        McpServerProperties.Client client = token == null ? null : findClient(token);
        if (client == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing bearer token");
            return;
        }
        Set<String> grantedScopes = scopes(client.getScopes());
        Set<String> requiredScopes = scopes(properties.getRequiredScopes());
        if (!grantedScopes.containsAll(requiredScopes)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "insufficient MCP scopes");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, client.getName());
        request.setAttribute(SCOPES_ATTRIBUTE, Set.copyOf(grantedScopes));
        filterChain.doFilter(request, response);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length());
        return token.isBlank() || token.contains(" ") ? null : token;
    }

    private McpServerProperties.Client findClient(String token) {
        byte[] actual = token.getBytes(StandardCharsets.UTF_8);
        McpServerProperties.Client matched = null;
        for (McpServerProperties.Client client : properties.getClients()) {
            if (client == null || client.getToken() == null) {
                continue;
            }
            byte[] expected = client.getToken().getBytes(StandardCharsets.UTF_8);
            // 继续比较所有凭据，避免因提前返回暴露 token 前缀或凭据位置。
            if (MessageDigest.isEqual(expected, actual) && matched == null) {
                matched = client;
            }
        }
        return matched;
    }

    private Set<String> scopes(Object values) {
        if (values instanceof Iterable<?> iterable) {
            return java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
                    .map(String::valueOf)
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toSet());
        }
        if (values instanceof String value) {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
