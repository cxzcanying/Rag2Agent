package com.rag2agent.bootstrap.tool;

import com.rag2agent.infra.ai.model.ToolDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 工具注册表：聚合所有内部工具，统一转成发给模型的 ToolDef 列表。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolList) {
        this.tools = toolList.stream().collect(Collectors.toMap(
                tool -> tool.descriptor().name(),
                tool -> tool,
                (a, b) -> a,
                LinkedHashMap::new));
    }

    public List<ToolDef> toolDefs() {
        return tools.values().stream()
                .map(tool -> new ToolDef(
                        tool.descriptor().name(),
                        tool.descriptor().description(),
                        tool.descriptor().parameters()))
                .toList();
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean requiresApproval(String name) {
        Tool tool = tools.get(name);
        return tool != null && tool.descriptor().requiresApproval();
    }
}
