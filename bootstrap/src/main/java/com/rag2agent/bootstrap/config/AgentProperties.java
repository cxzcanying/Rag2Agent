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
    /** off/auto/minimal/low/medium/high/max；auto 按问题复杂度选择。 */
    private String reasoningEffort = "off";
    /** 团队研究固定启动的分析 Worker 数量，Reviewer 在全部结果返回后执行。 */
    private int teamAnalystWorkers = 2;
    /** 每个 Worker 的模型输入与输出总 Token 上限。 */
    private int teamWorkerTokenBudget = 12_000;
    /** 每个 Worker 可调用的工具次数上限。 */
    private int teamWorkerToolBudget = 2;
    /** 每个 Worker 从启动到返回的硬时限。 */
    private long teamWorkerTimeoutMillis = 60_000;

}
