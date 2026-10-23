package com.rag2agent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.security")
public class SecurityProperties {

    private List<String> adminUsernames = new ArrayList<>();
    private List<String> internalCidrs = new ArrayList<>(List.of(
            "127.0.0.0/8",
            "::1/128",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16"));

    public List<String> getAdminUsernames() {
        return adminUsernames;
    }

    public void setAdminUsernames(List<String> adminUsernames) {
        this.adminUsernames = adminUsernames;
    }

    public List<String> getInternalCidrs() {
        return internalCidrs;
    }

    public void setInternalCidrs(List<String> internalCidrs) {
        this.internalCidrs = internalCidrs;
    }
}
