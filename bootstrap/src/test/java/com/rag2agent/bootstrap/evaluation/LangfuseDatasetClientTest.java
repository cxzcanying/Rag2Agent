package com.rag2agent.bootstrap.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.LangfuseProperties;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class LangfuseDatasetClientTest {

    @Test
    void mapsLangfuseItemToEvaluationCase() throws Exception {
        LangfuseProperties properties = new LangfuseProperties();
        properties.setEnabled(true);
        properties.setPublicKey("public");
        properties.setSecretKey("secret");
        LangfuseDatasetClient client = new LangfuseDatasetClient(
                properties, new ObjectMapper(), mock(HttpClient.class));
        var item = new ObjectMapper().readTree("""
                {"id":"item-1","input":{"question":"营收是多少"},
                 "expectedOutput":{"answer":"100"},
                 "metadata":{"goldenDocumentIds":[9],"caseType":"TABLE",
                   "evidence":{"tableId":"table-1","rowIds":["row-17"],
                               "headers":["区域","金额"]}}}
                """);

        var result = client.toCase(item);

        assertEquals("营收是多少", result.question());
        assertEquals("100", result.expectedAnswer());
        assertEquals("TABLE", result.caseType());
        assertEquals(9L, result.goldenDocumentIds().getFirst());
        assertEquals("table-1", result.evidence().get("tableId"));
    }
}
