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

    private final McpServerProperties properties;

    public McpAuthorizationFilter(McpServerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!validBearer(authorization)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing bearer token");
            return;
        }
        Set<String> grantedScopes = scopes(request.getHeader("X-MCP-Scopes"));
        Set<String> requiredScopes = scopes(properties.getRequiredScopes());
        Set<String> serverScopes = scopes(properties.getTokenScopes());
        if (!serverScopes.containsAll(requiredScopes) || !grantedScopes.containsAll(requiredScopes)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "insufficient MCP scopes");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean validBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = ("Bearer " + properties.getToken()).getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
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
