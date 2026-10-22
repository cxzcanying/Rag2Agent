package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentWorkerBudgetTest {

    @Test
    void blocksModelAndToolCallsBeforeBudgetIsExceeded() {
        AgentProperties properties = new AgentProperties();
        properties.setTeamWorkerTokenBudget(3);
        properties.setTeamWorkerToolBudget(1);
        ContextCompactor compactor = new ContextCompactor(properties.getSummaryMaxChars());
        AgentWorkerBudget budget = new AgentWorkerBudget(properties, compactor);

        assertThrows(BusinessException.class, () -> budget.beginModel(
                List.of(new ChatMessage("user", "这是一个超过预算的提示词"))));

        AgentWorkerBudget toolBudget = new AgentWorkerBudget(properties, compactor);
        toolBudget.useTool();
        BusinessException toolExceeded =
                assertThrows(BusinessException.class, toolBudget::useTool);
        assertEquals("429", toolExceeded.errorCode().code());
    }
}
