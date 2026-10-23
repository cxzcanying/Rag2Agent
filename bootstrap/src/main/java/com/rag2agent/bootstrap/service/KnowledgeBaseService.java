package com.rag2agent.bootstrap.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseMemberView;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseView;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.entity.KnowledgeBase;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.bootstrap.mapper.KnowledgeBaseMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "ORGANIZATION", "TENANT");
    private static final Set<String> MEMBER_ROLES = Set.of("VIEWER", "EDITOR", "ADMIN");

    private final KnowledgeBaseMapper kbMapper;
    private final AppUserMapper userMapper;
    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    public KnowledgeBaseService(
            KnowledgeBaseMapper kbMapper,
            AppUserMapper userMapper,
            JdbcTemplate jdbc,
            SecurityAuditService audit) {
        this.kbMapper = kbMapper;
        this.userMapper = userMapper;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public KnowledgeBaseView create(Long userId, CreateKnowledgeBaseRequest request) {
        AppUser user = requireUser(userId);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setOwnerUserId(userId);
        kb.setTenantId(user.getTenantId());
        kb.setOrgId(user.getOrgId());
        kb.setVisibility(normalizeVisibility(request.visibility()));
        kb.setAccessRole("OWNER");
        kbMapper.insert(kb);
        audit.allow(user.getTenantId(), userId, "KB_CREATE", "KNOWLEDGE_BASE", kb.getId(),
                Map.of("visibility", kb.getVisibility()));
        return KnowledgeBaseView.from(kb);
    }

    public List<KnowledgeBaseView> list(Long userId) {
        AppUser user = requireUser(userId);
        return kbMapper.selectReadable(userId, user.getTenantId(), user.getOrgId())
                .stream().map(KnowledgeBaseView::from).toList();
    }

    public void requireReadable(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countReadable(kbId, userId, user.getTenantId(), user.getOrgId()) != 1) {
            audit.deny(user.getTenantId(), userId, "KB_READ", "KNOWLEDGE_BASE", kbId,
                    "ACCESS_DENIED", Map.of());
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void requireWritable(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countWritable(kbId, userId, user.getTenantId()) != 1) {
            audit.deny(user.getTenantId(), userId, "KB_WRITE", "KNOWLEDGE_BASE", kbId,
                    "ACCESS_DENIED", Map.of());
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void requireAdmin(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countAdmin(kbId, userId, user.getTenantId()) != 1) {
            audit.deny(user.getTenantId(), userId, "KB_ADMIN", "KNOWLEDGE_BASE", kbId,
                    "ACCESS_DENIED", Map.of());
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void requireOwner(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countOwner(kbId, userId, user.getTenantId()) != 1) {
            audit.deny(user.getTenantId(), userId, "KB_OWNER", "KNOWLEDGE_BASE", kbId,
                    "ACCESS_DENIED", Map.of());
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    @Transactional
    public void updateVisibility(Long userId, Long kbId, String visibility) {
        requireOwner(userId, kbId);
        AppUser actor = requireUser(userId);
        String normalized = normalizeVisibility(visibility);
        int updated = jdbc.update("""
                UPDATE knowledge_base
                SET visibility = ?, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                """, normalized, kbId, actor.getTenantId(), userId);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        audit.allow(actor.getTenantId(), userId, "KB_VISIBILITY_UPDATE",
                "KNOWLEDGE_BASE", kbId, Map.of("visibility", normalized));
    }

    public List<KnowledgeBaseMemberView> listMembers(Long userId, Long kbId) {
        requireAdmin(userId, kbId);
        return jdbc.query("""
                SELECT member.user_id, app_user.username, app_user.nickname,
                       member.role, member.created_at
                FROM knowledge_base_member member
                JOIN app_user ON app_user.id = member.user_id
                WHERE member.kb_id = ?
                ORDER BY member.created_at, member.user_id
                """, (rs, rowNum) -> new KnowledgeBaseMemberView(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("nickname"),
                rs.getString("role"),
                instant(rs.getTimestamp("created_at"))), kbId);
    }

    @Transactional
    public KnowledgeBaseMemberView grant(
            Long userId, Long kbId, Long targetUserId, String username, String role) {
        requireAdmin(userId, kbId);
        AppUser actor = requireUser(userId);
        AppUser target = targetUserId == null
                ? findUserByUsername(username)
                : userMapper.selectById(targetUserId);
        if (target == null || !actor.getTenantId().equals(target.getTenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标用户不存在");
        }
        String normalizedRole = normalizeRole(role);
        boolean owner = kbMapper.countOwner(kbId, userId, actor.getTenantId()) == 1;
        if ("ADMIN".equals(normalizedRole) && !owner) {
            audit.deny(actor.getTenantId(), userId, "KB_MEMBER_GRANT",
                    "KNOWLEDGE_BASE", kbId,
                    "ADMIN_GRANT_REQUIRES_OWNER",
                    Map.of("targetUserId", target.getId(), "role", normalizedRole));
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有知识库 Owner 可以任命管理员");
        }
        jdbc.update("""
                INSERT INTO knowledge_base_member (kb_id, user_id, tenant_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (kb_id, user_id)
                DO UPDATE SET role = EXCLUDED.role, updated_at = now()
                """, kbId, target.getId(), actor.getTenantId(), normalizedRole);
        audit.allow(actor.getTenantId(), userId, "KB_MEMBER_GRANT",
                "KNOWLEDGE_BASE", kbId,
                Map.of("targetUserId", target.getId(), "role", normalizedRole));
        return new KnowledgeBaseMemberView(
                target.getId(), target.getUsername(), target.getNickname(),
                normalizedRole, Instant.now());
    }

    @Transactional
    public boolean revoke(Long userId, Long kbId, Long targetUserId) {
        requireAdmin(userId, kbId);
        AppUser actor = requireUser(userId);
        boolean owner = kbMapper.countOwner(kbId, userId, actor.getTenantId()) == 1;
        String targetRole = memberRole(kbId, targetUserId);
        if ("OWNER".equals(targetRole)
                || ("ADMIN".equals(targetRole) && !owner)) {
            audit.deny(actor.getTenantId(), userId, "KB_MEMBER_REVOKE",
                    "KNOWLEDGE_BASE", kbId,
                    "ADMIN_REVOKE_REQUIRES_OWNER",
                    Map.of("targetUserId", targetUserId, "role", targetRole));
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有知识库 Owner 可以撤销管理员");
        }
        boolean revoked = jdbc.update("""
                DELETE FROM knowledge_base_member member
                USING knowledge_base kb
                WHERE member.kb_id = kb.id
                  AND kb.id = ?
                  AND kb.tenant_id = ?
                  AND member.user_id = ?
                  AND member.user_id <> kb.owner_user_id
                """, kbId, actor.getTenantId(), targetUserId) == 1;
        if (revoked) {
            audit.allow(actor.getTenantId(), userId, "KB_MEMBER_REVOKE",
                    "KNOWLEDGE_BASE", kbId,
                    Map.of("targetUserId", targetUserId, "role", targetRole));
        }
        return revoked;
    }

    private AppUser requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null || user.getTenantId() == null || user.getOrgId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户租户信息无效");
        }
        return user;
    }

    private AppUser findUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "userId 或 username 必须提供一个");
        }
        return userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username.trim()));
    }

    private void requireKnowledgeBaseId(Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kbId 必须为正数");
        }
    }

    private String normalizeVisibility(String visibility) {
        String value = visibility == null || visibility.isBlank()
                ? "PRIVATE"
                : visibility.trim().toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(value)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "visibility 必须为 PRIVATE、ORGANIZATION 或 TENANT");
        }
        return value;
    }

    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!MEMBER_ROLES.contains(value)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "role 必须为 VIEWER、EDITOR 或 ADMIN");
        }
        return value;
    }

    private Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String memberRole(Long kbId, Long targetUserId) {
        List<String> roles = jdbc.query("""
                SELECT CASE
                           WHEN kb.owner_user_id = member.user_id THEN 'OWNER'
                           ELSE member.role
                       END
                FROM knowledge_base_member member
                JOIN knowledge_base kb ON kb.id = member.kb_id
                WHERE member.kb_id = ? AND member.user_id = ?
                """, (rs, rowNum) -> rs.getString(1), kbId, targetUserId);
        return roles.isEmpty() ? null : roles.getFirst();
    }
}
