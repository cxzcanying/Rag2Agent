package com.rag2agent.bootstrap.security;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.config.SecurityProperties;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.bootstrap.service.SecurityAuditService;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {

    private final SecurityProperties properties;
    private final InternalNetworkPolicy internalNetworkPolicy;
    private final AppUserMapper userMapper;
    private final SecurityAuditService audit;

    public AdminAccessService(
            SecurityProperties properties,
            InternalNetworkPolicy internalNetworkPolicy,
            AppUserMapper userMapper,
            SecurityAuditService audit) {
        this.properties = properties;
        this.internalNetworkPolicy = internalNetworkPolicy;
        this.userMapper = userMapper;
        this.audit = audit;
    }

    public void requireAccess(HttpServletRequest request, boolean allowInternal) {
        if (allowInternal && internalNetworkPolicy.contains(request.getRemoteAddr())) {
            return;
        }
        Long userId;
        try {
            userId = StpUtil.getLoginIdAsLong();
        } catch (NotLoginException exception) {
            audit.deny(null, null, "ADMIN_ACCESS", "ENDPOINT", null,
                    "NOT_LOGGED_IN", Map.of("path", request.getRequestURI()));
            throw exception;
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null || !properties.getAdminUsernames().contains(user.getUsername())) {
            audit.deny(
                    user == null ? null : user.getTenantId(),
                    userId,
                    "ADMIN_ACCESS",
                    "ENDPOINT",
                    null,
                    "NOT_ADMIN",
                    Map.of("path", request.getRequestURI()));
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
