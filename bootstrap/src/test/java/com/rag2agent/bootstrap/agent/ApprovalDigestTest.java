package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ApprovalDigestTest {

    @Test
    void canonicalizesArgumentOrderButDetectsMaterialChanges() {
        String first = ApprovalDigest.compute(
                1L, 2L, "delete_document", "{\"document_id\":7,\"reason\":\"test\"}");
        String reordered = ApprovalDigest.compute(
                1L, 2L, "delete_document", "{\"reason\":\"test\",\"document_id\":7}");
        String changed = ApprovalDigest.compute(
                1L, 2L, "delete_document", "{\"document_id\":8,\"reason\":\"test\"}");

        assertEquals(first, reordered);
        assertNotEquals(first, changed);
    }
}
