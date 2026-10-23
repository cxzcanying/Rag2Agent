package com.rag2agent.bootstrap.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void nestedScopesRestorePreviousState() {
        TenantContext.set(7);

        TenantContext.runAs(9, () -> assertEquals(9, TenantContext.getTenantId()));
        assertEquals(7, TenantContext.getTenantId());

        TenantContext.runAsSystem(() -> assertTrue(TenantContext.isSystem()));
        assertFalse(TenantContext.isSystem());
        assertEquals(7, TenantContext.getTenantId());

        TenantContext.clear();
        assertNull(TenantContext.getTenantId());
    }
}
