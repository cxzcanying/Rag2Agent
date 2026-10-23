package com.rag2agent.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.bootstrap.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class InternalNetworkPolicyTest {

    @Test
    void matchesConfiguredIpv4AndLoopbackRanges() {
        InternalNetworkPolicy policy = new InternalNetworkPolicy(new SecurityProperties());

        assertTrue(policy.contains("127.0.0.1"));
        assertTrue(policy.contains("172.20.0.8"));
        assertTrue(policy.contains("192.168.1.9"));
        assertTrue(policy.contains("::1"));
        assertFalse(policy.contains("203.0.113.10"));
    }
}
