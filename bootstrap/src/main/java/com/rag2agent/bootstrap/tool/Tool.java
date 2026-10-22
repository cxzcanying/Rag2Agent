package com.rag2agent.bootstrap.tool;

import java.util.Map;

/**
 * 内部工具接口。execute 返回给模型的工具结果（通常是 JSON 字符串）。
 */
public interface Tool {

    ToolDescriptor descriptor();

    /** 工具执行前的资源归属校验；远程工具还必须在服务端重复校验。 */
    default void validateAccess(Long userId, Map<String, Object> arguments) {}

    String execute(Map<String, Object> arguments);

    /** 需要用户上下文的后台执行入口；默认保持既有工具契约。 */
    default String execute(Long userId, Map<String, Object> arguments) {
        return execute(arguments);
    }
}
