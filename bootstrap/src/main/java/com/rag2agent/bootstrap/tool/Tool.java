package com.rag2agent.bootstrap.tool;

import java.util.Map;

/**
 * 内部工具接口。execute 返回给模型的工具结果（通常是 JSON 字符串）。
 */
public interface Tool {

    ToolDescriptor descriptor();

    String execute(Map<String, Object> arguments);
}
