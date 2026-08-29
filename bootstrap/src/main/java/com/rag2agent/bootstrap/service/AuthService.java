package com.rag2agent.bootstrap.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginResponse;
import com.rag2agent.bootstrap.dto.AuthDtos.RegisterRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.UserView;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.bootstrap.config.RateLimitProperties;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/**
 * @author 21311
 */
@Service
public class AuthService {

    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final RateLimitProperties rateLimitProperties;
    private static final DefaultRedisScript<Long> LOGIN_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count", Long.class);

    public AuthService(AppUserMapper userMapper, PasswordEncoder passwordEncoder,
            StringRedisTemplate redis, RateLimitProperties rateLimitProperties) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.rateLimitProperties = rateLimitProperties;
    }

    public UserView register(RegisterRequest request) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname() : request.username());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // selectCount + insert 不是原子操作，并发注册时以数据库唯一约束为最终裁判。
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        return UserView.from(user);
    }

    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        String lockKey = "rag2agent:auth:locked:" + username;
        String failureKey = "rag2agent:auth:failures:" + username;
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请稍后重试");
        }
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            try {
                Long failures = redis.execute(LOGIN_FAILURE_SCRIPT, List.of(failureKey),
                        String.valueOf(rateLimitProperties.getLoginFailureWindowSeconds()));
                if (failures != null && failures >= rateLimitProperties.getLoginFailureLimit()) {
                    redis.opsForValue().set(lockKey, "1",
                            java.time.Duration.ofSeconds(rateLimitProperties.getLoginLockSeconds()));
                }
            } catch (RuntimeException ignored) {
                // 认证依赖异常时维持原有错误语义，不暴露 Redis 状态。
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }
        try {
            redis.delete(failureKey);
            redis.delete(lockKey);
        } catch (RuntimeException ignored) {
            // 登录成功不应因清理失败计数的旁路依赖而失败。
        }
        StpUtil.login(user.getId());
        return new LoginResponse(StpUtil.getTokenValue(), UserView.from(user));
    }

    public UserView me(Long userId) {
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return UserView.from(user);
    }
}
