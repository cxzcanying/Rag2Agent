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
    private long toolTimeoutMillis = 10000;
    /** 单次执行内非审批工具调用的全局上限，超过即强制总结，防止模型死循环反复调工具。 */
    private int maxToolCalls = 20;
    /** 审批挂起超过该秒数后由后台自动置为终态，避免一直积压 WAITING_APPROVAL。 */
    private long approvalTimeoutSeconds = 30 * 60;

}
