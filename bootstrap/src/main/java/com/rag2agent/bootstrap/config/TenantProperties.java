package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 新注册用户的默认租户归属；独立租户开户流程接入后由身份提供方覆盖。 */
@ConfigurationProperties(prefix = "rag2agent.tenancy")
public class TenantProperties {

    private long defaultTenantId = 1;
    private long defaultOrganizationId = 1;

    public long getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(long defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public long getDefaultOrganizationId() {
        return defaultOrganizationId;
    }

    public void setDefaultOrganizationId(long defaultOrganizationId) {
        this.defaultOrganizationId = defaultOrganizationId;
    }
}
