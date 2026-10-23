package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.config.RateLimitProperties;
import com.rag2agent.bootstrap.config.RegistrationProperties;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.RegisterRequest;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    @Test
    void redisAvailableLocksAfterFailureLimit() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginFailureLimit(3);
        when(redis.hasKey("rag2agent:auth:locked:alice")).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(users.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new AuthService(users, encoder, redis, properties,
                        new RegistrationProperties(), org.mockito.Mockito.mock(SecurityAuditService.class))
                        .login(new LoginRequest("alice", "bad")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        verify(values).set("rag2agent:auth:locked:alice", "1", Duration.ofSeconds(900));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> window = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class), anyList(), window.capture());
        assertEquals("900", window.getValue());
    }

    @Test
    void redisUnavailableKeepsStableAuthenticationError() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        when(users.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new AuthService(
                        users, encoder, redis, new RateLimitProperties(),
                        new RegistrationProperties(), org.mockito.Mockito.mock(SecurityAuditService.class))
                        .login(new LoginRequest("alice", "bad")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        assertEquals("用户名或密码错误", exception.getMessage());
    }

    @Test
    void lockedUserIsRejectedBeforeDatabaseLookup() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(redis.hasKey("rag2agent:auth:locked:alice")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new AuthService(users, org.mockito.Mockito.mock(PasswordEncoder.class), redis,
                        new RateLimitProperties(), new RegistrationProperties(),
                        org.mockito.Mockito.mock(SecurityAuditService.class))
                        .login(new LoginRequest("alice", "bad")));

        assertEquals(ErrorCode.RATE_LIMITED, exception.errorCode());
        verify(users, never()).selectOne(any());
    }

    @Test
    void successfulLoginClearsFailureWindowAndLock() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        AppUser user = new AppUser();
        user.setId(17L);
        user.setUsername("alice");
        user.setPasswordHash("hash");
        when(redis.hasKey(anyString())).thenReturn(false);
        when(users.selectOne(any())).thenReturn(user);
        when(encoder.matches("good", "hash")).thenReturn(true);

        assertThrows(RuntimeException.class, () -> new AuthService(
                users, encoder, redis, new RateLimitProperties(), new RegistrationProperties(),
                org.mockito.Mockito.mock(SecurityAuditService.class))
                .login(new LoginRequest("alice", "good")));

        verify(redis).delete("rag2agent:auth:failures:alice");
        verify(redis).delete("rag2agent:auth:locked:alice");
    }

    @Test
    void registrationIsDisabledByDefault() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        SecurityAuditService audit = org.mockito.Mockito.mock(SecurityAuditService.class);
        AuthService service = new AuthService(
                users,
                org.mockito.Mockito.mock(PasswordEncoder.class),
                org.mockito.Mockito.mock(StringRedisTemplate.class),
                new RateLimitProperties(),
                new RegistrationProperties(),
                audit);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.register(new RegisterRequest("alice", "secret1", "", "")));

        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode());
        verify(users, never()).selectCount(any());
    }

    @Test
    void validInviteBindsTenantAndOrganization() {
        AppUserMapper users = org.mockito.Mockito.mock(AppUserMapper.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        SecurityAuditService audit = org.mockito.Mockito.mock(SecurityAuditService.class);
        RegistrationProperties registration = new RegistrationProperties();
        registration.setMode(RegistrationProperties.Mode.INVITE);
        registration.setInviteCode("invite-2026");
        registration.setTenantId(31L);
        registration.setOrganizationId(32L);
        when(users.selectCount(any())).thenReturn(0L);
        when(encoder.encode("secret1")).thenReturn("hash");
        doAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(users).insert(any(AppUser.class));
        AuthService service = new AuthService(
                users, encoder, org.mockito.Mockito.mock(StringRedisTemplate.class),
                new RateLimitProperties(), registration, audit);

        var created = service.register(
                new RegisterRequest("alice", "secret1", "Alice", "invite-2026"));

        assertEquals(31L, created.tenantId());
        assertEquals(32L, created.orgId());
        verify(audit).allow(
                eq(31L), eq(99L), eq("USER_REGISTER"), eq("USER"), eq(99L), any());
    }
}
