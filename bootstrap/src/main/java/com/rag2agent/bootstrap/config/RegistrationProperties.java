package com.rag2agent.bootstrap.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自助注册默认关闭；邀请码只决定能否创建账号，并绑定到明确的租户和组织。 */
@ConfigurationProperties(prefix = "rag2agent.registration")
public class RegistrationProperties {

    public enum Mode {
        DISABLED,
        INVITE
    }

    private Mode mode = Mode.DISABLED;
    private String inviteCode = "";
    private long tenantId = 1;
    private long organizationId = 1;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DISABLED : mode;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode == null ? "" : inviteCode;
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
    }

    public long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(long organizationId) {
        this.organizationId = organizationId;
    }

    public boolean acceptsInvite(String candidate) {
        if (mode != Mode.INVITE || inviteCode.isBlank() || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                inviteCode.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
