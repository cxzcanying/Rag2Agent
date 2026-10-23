package com.rag2agent.bootstrap.tenancy;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 在 Sa-Token 完成请求上下文初始化后再装载租户，避免 DB 查询缺少 RLS 上下文。 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    private final AppUserMapper userMapper;

    public TenantContextInterceptor(AppUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (StpUtil.isLogin()) {
            Long tenantId = sessionTenantId();
            if (tenantId == null) {
                AppUser user = userMapper.selectById(StpUtil.getLoginIdAsLong());
                tenantId = user == null ? null : user.getTenantId();
            }
            if (tenantId != null && tenantId > 0) {
                TenantContext.set(tenantId);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        TenantContext.clear();
    }

    private Long sessionTenantId() {
        try {
            Object value = StpUtil.getSession().get("tenantId");
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text);
            }
        } catch (RuntimeException ignored) {
            // 老会话没有租户字段时从用户表补读。
        }
        return null;
    }
}
