package com.rag2agent.mcp.config;

import com.rag2agent.mcp.http.McpAuthorizationFilter;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/** MCP 2.0 Streamable HTTP 服务：官方协议生命周期 + 应用级认证过滤器。 */
@Configuration
@ConditionalOnProperty(prefix = "rag2agent.mcp.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfiguration {

    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(
            McpServerProperties properties) {
        var security = DefaultServerTransportSecurityValidator.builder();
        properties.getAllowedOrigins().forEach(security::allowedOrigin);
        properties.getAllowedHosts().forEach(security::allowedHost);
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(properties.getEndpoint())
                .keepAliveInterval(Duration.ofSeconds(properties.getKeepAliveSeconds()))
                .securityValidator(security.build())
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transport,
            McpServerProperties properties) {
        return McpServer.sync(transport)
                .serverInfo(properties.getServerName(), properties.getServerVersion())
                .instructions("RAG2Agent 企业知识库 MCP 服务")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .prompts(false)
                        .build())
                .tools(echoTool())
                .resources(runtimeInfoResource(properties))
                .prompts(researchPrompt())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServlet(
            HttpServletStreamableServerTransportProvider transport,
            McpServerProperties properties) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(transport, properties.getEndpoint());
        registration.setName("mcpStreamableServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<McpAuthorizationFilter> mcpAuthorization(
            McpServerProperties properties, Environment environment) {
        validateCredentials(properties, environment);
        FilterRegistrationBean<McpAuthorizationFilter> registration =
                new FilterRegistrationBean<>(new McpAuthorizationFilter(properties));
        registration.addUrlPatterns(properties.getEndpoint());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static void validateCredentials(McpServerProperties properties, Environment environment) {
        if (properties.getClients().isEmpty()) {
            throw new IllegalStateException("MCP 服务至少需要一个服务端凭据");
        }
        boolean production = environment.acceptsProfiles(Profiles.of("prod", "production"));
        var names = new java.util.HashSet<String>();
        for (McpServerProperties.Client client : properties.getClients()) {
            if (client == null || !StringUtils.hasText(client.getName())
                    || !StringUtils.hasText(client.getToken())) {
                throw new IllegalStateException("MCP 凭据名和 token 不能为空");
            }
            if (!names.add(client.getName())) {
                throw new IllegalStateException("MCP 凭据名不能重复: " + client.getName());
            }
            if (client.getScopes() == null || client.getScopes().isEmpty()) {
                throw new IllegalStateException("MCP 凭据必须配置至少一个 scope");
            }
            if (production && "dev-mcp-token".equals(client.getToken())) {
                throw new IllegalStateException("正式环境禁止使用默认 MCP token");
            }
        }
    }

    private McpServerFeatures.SyncToolSpecification echoTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", List.of("text"));
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("echo", schema)
                        .description("返回调用方提交的文本，用于 MCP 连通性与鉴权验证")
                        .annotations(McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(true)
                                .build())
                        .build())
                .callHandler((exchange, request) -> {
                    Object text = request.arguments().get("text");
                    if (!(text instanceof String value) || value.isBlank()) {
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder("text 不能为空").build()))
                                .isError(true)
                                .build();
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(value).build()))
                            .isError(false)
                            .build();
                })
                .build();
    }

    private McpServerFeatures.SyncResourceSpecification runtimeInfoResource(
            McpServerProperties properties) {
        String uri = "rag2agent://runtime/info";
        return new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder(uri, "RAG2Agent Runtime")
                        .description("MCP 服务名称、版本和协议能力")
                        .mimeType("application/json")
                        .build(),
                (exchange, request) -> McpSchema.ReadResourceResult.builder(List.of(
                                McpSchema.TextResourceContents.builder(
                                                uri,
                                                """
                                                {"name":"%s","version":"%s","transport":"streamable-http","protocol":"2025-11-25"}
                                                """.formatted(
                                                        properties.getServerName(),
                                                        properties.getServerVersion()))
                                        .mimeType("application/json")
                                        .build()))
                        .build());
    }

    private McpServerFeatures.SyncPromptSpecification researchPrompt() {
        return new McpServerFeatures.SyncPromptSpecification(
                McpSchema.Prompt.builder("research_brief")
                        .description("生成限定证据范围的企业研究任务说明")
                        .arguments(List.of(
                                McpSchema.PromptArgument.builder("question")
                                        .description("研究问题")
                                        .required(true)
                                        .build(),
                                McpSchema.PromptArgument.builder("constraints")
                                        .description("时间、组织或证据约束")
                                        .required(false)
                                        .build()))
                        .build(),
                (exchange, request) -> {
                    String question = String.valueOf(
                            request.arguments().getOrDefault("question", ""));
                    String constraints = String.valueOf(
                            request.arguments().getOrDefault("constraints", "无额外约束"));
                    String prompt = """
                            基于企业知识库研究以下问题：%s
                            约束：%s
                            要求：列出来源证据、冲突信息、证据缺口和最终结论。
                            """.formatted(question, constraints);
                    return McpSchema.GetPromptResult.builder(List.of(
                                    McpSchema.PromptMessage.builder(
                                                    McpSchema.Role.USER,
                                                    McpSchema.TextContent.builder(prompt).build())
                                            .build()))
                            .description("企业研究提示词")
                            .build();
                });
    }
}
