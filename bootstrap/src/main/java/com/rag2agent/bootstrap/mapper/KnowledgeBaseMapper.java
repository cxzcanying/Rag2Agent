package com.rag2agent.bootstrap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag2agent.bootstrap.entity.KnowledgeBase;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("""
            SELECT kb.*,
                   CASE
                       WHEN kb.owner_user_id = #{userId} THEN 'OWNER'
                       ELSE COALESCE((
                           SELECT member.role
                           FROM knowledge_base_member member
                           WHERE member.kb_id = kb.id
                             AND member.user_id = #{userId}
                             AND member.tenant_id = #{tenantId}
                       ), 'VIEWER')
                   END AS access_role
            FROM knowledge_base kb
            WHERE kb.tenant_id = #{tenantId}
              AND (
                  kb.owner_user_id = #{userId}
                  OR kb.visibility = 'TENANT'
                  OR (kb.visibility = 'ORGANIZATION' AND kb.org_id = #{orgId})
                  OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_member member
                      WHERE member.kb_id = kb.id
                        AND member.user_id = #{userId}
                        AND member.tenant_id = #{tenantId}
                  )
              )
            ORDER BY kb.id DESC
            """)
    List<KnowledgeBase> selectReadable(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("orgId") Long orgId);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_base kb
            WHERE kb.id = #{kbId}
              AND kb.tenant_id = #{tenantId}
              AND (
                  kb.owner_user_id = #{userId}
                  OR kb.visibility = 'TENANT'
                  OR (kb.visibility = 'ORGANIZATION' AND kb.org_id = #{orgId})
                  OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_member member
                      WHERE member.kb_id = kb.id
                        AND member.user_id = #{userId}
                        AND member.tenant_id = #{tenantId}
                  )
              )
            """)
    int countReadable(
            @Param("kbId") Long kbId,
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("orgId") Long orgId);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_base kb
            WHERE kb.id = #{kbId}
              AND kb.tenant_id = #{tenantId}
              AND (
                  kb.owner_user_id = #{userId}
                  OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_member member
                      WHERE member.kb_id = kb.id
                        AND member.user_id = #{userId}
                        AND member.tenant_id = #{tenantId}
                        AND member.role IN ('EDITOR', 'ADMIN')
                  )
              )
            """)
    int countWritable(
            @Param("kbId") Long kbId,
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_base kb
            WHERE kb.id = #{kbId}
              AND kb.tenant_id = #{tenantId}
              AND (
                  kb.owner_user_id = #{userId}
                  OR EXISTS (
                      SELECT 1
                      FROM knowledge_base_member member
                      WHERE member.kb_id = kb.id
                        AND member.user_id = #{userId}
                        AND member.tenant_id = #{tenantId}
                        AND member.role = 'ADMIN'
                  )
              )
            """)
    int countAdmin(
            @Param("kbId") Long kbId,
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId);
}
