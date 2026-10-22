package com.rag2agent.bootstrap.service;

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
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {

    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "ORGANIZATION", "TENANT");
    private static final Set<String> MEMBER_ROLES = Set.of("VIEWER", "EDITOR", "ADMIN");

    private final KnowledgeBaseMapper kbMapper;
    private final AppUserMapper userMapper;
    private final JdbcTemplate jdbc;

    public KnowledgeBaseService(
            KnowledgeBaseMapper kbMapper, AppUserMapper userMapper, JdbcTemplate jdbc) {
        this.kbMapper = kbMapper;
        this.userMapper = userMapper;
        this.jdbc = jdbc;
    }

    public KnowledgeBaseView create(Long userId, CreateKnowledgeBaseRequest request) {
        AppUser user = requireUser(userId);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setOwnerUserId(userId);
        kb.setTenantId(user.getTenantId());
        kb.setOrgId(user.getOrgId());
        kb.setVisibility(normalizeVisibility(request.visibility()));
        kbMapper.insert(kb);
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
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void requireWritable(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countWritable(kbId, userId, user.getTenantId()) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void requireAdmin(Long userId, Long kbId) {
        AppUser user = requireUser(userId);
        requireKnowledgeBaseId(kbId);
        if (kbMapper.countAdmin(kbId, userId, user.getTenantId()) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    public void updateVisibility(Long userId, Long kbId, String visibility) {
        requireAdmin(userId, kbId);
        int updated = jdbc.update("""
                UPDATE knowledge_base
                SET visibility = ?, updated_at = now()
                WHERE id = ?
                """, normalizeVisibility(visibility), kbId);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
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

    public KnowledgeBaseMemberView grant(
            Long userId, Long kbId, Long targetUserId, String role) {
        requireAdmin(userId, kbId);
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 必须为正数");
        }
        AppUser actor = requireUser(userId);
        AppUser target = userMapper.selectById(targetUserId);
        if (target == null || !actor.getTenantId().equals(target.getTenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标用户不存在");
        }
        String normalizedRole = normalizeRole(role);
        jdbc.update("""
                INSERT INTO knowledge_base_member (kb_id, user_id, tenant_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (kb_id, user_id)
                DO UPDATE SET role = EXCLUDED.role, updated_at = now()
                """, kbId, targetUserId, actor.getTenantId(), normalizedRole);
        return new KnowledgeBaseMemberView(
                target.getId(), target.getUsername(), target.getNickname(),
                normalizedRole, Instant.now());
    }

    public boolean revoke(Long userId, Long kbId, Long targetUserId) {
        requireAdmin(userId, kbId);
        return jdbc.update("""
                DELETE FROM knowledge_base_member member
                USING knowledge_base kb
                WHERE member.kb_id = kb.id
                  AND kb.id = ?
                  AND member.user_id = ?
                  AND member.user_id <> kb.owner_user_id
                """, kbId, targetUserId) == 1;
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
}
