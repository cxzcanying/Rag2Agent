package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.entity.KnowledgeBase;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.bootstrap.mapper.KnowledgeBaseMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeBaseServiceTest {

    @Test
    void createBindsKnowledgeBaseToCurrentTenantAndOrganization() {
        KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        AppUser user = user(7L, 11L, 12L);
        when(users.selectById(7L)).thenReturn(user);
        KnowledgeBaseService service =
                new KnowledgeBaseService(bases, users, mock(JdbcTemplate.class));

        var view = service.create(
                7L, new CreateKnowledgeBaseRequest("财务", "季度数据", "tenant"));

        ArgumentCaptor<KnowledgeBase> created = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(bases).insert(created.capture());
        assertEquals(11L, created.getValue().getTenantId());
        assertEquals(12L, created.getValue().getOrgId());
        assertEquals("TENANT", view.visibility());
        assertEquals("OWNER", view.accessRole());
    }

    @Test
    void tenantVisibilityGrantsReadButNotWrite() {
        KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        when(users.selectById(7L)).thenReturn(user(7L, 11L, 12L));
        when(bases.countReadable(3L, 7L, 11L, 12L)).thenReturn(1);
        when(bases.countWritable(3L, 7L, 11L)).thenReturn(0);
        KnowledgeBaseService service =
                new KnowledgeBaseService(bases, users, mock(JdbcTemplate.class));

        service.requireReadable(7L, 3L);
        BusinessException denied =
                assertThrows(BusinessException.class, () -> service.requireWritable(7L, 3L));

        assertEquals(ErrorCode.NOT_FOUND, denied.errorCode());
    }

    @Test
    void grantRejectsUserFromAnotherTenant() {
        KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(users.selectById(7L)).thenReturn(user(7L, 11L, 12L));
        when(users.selectById(8L)).thenReturn(user(8L, 21L, 22L));
        when(bases.countAdmin(3L, 7L, 11L)).thenReturn(1);
        KnowledgeBaseService service = new KnowledgeBaseService(bases, users, jdbc);

        assertThrows(
                BusinessException.class, () -> service.grant(7L, 3L, 8L, null, "VIEWER"));
    }

    private static AppUser user(long id, long tenantId, long orgId) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setTenantId(tenantId);
        user.setOrgId(orgId);
        return user;
    }
}
