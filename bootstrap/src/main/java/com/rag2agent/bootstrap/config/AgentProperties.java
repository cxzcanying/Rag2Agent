package com.rag2agent.bootstrap.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 上下文预算。先使用保守估算，后续可替换成真实 tokenizer。
 * @author 21311*/
@Setter
@Getter
@ConfigurationProperties(prefix = "rag2agent.agent")
public class AgentProperties {

    private int contextTokenBudget = 6000;
    private int maxInputChars = 12000;
    private int summaryMaxChars = 1200;
    private int maxOutputTokens = 1024;

}
