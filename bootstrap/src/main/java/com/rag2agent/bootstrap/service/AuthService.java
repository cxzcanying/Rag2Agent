package com.rag2agent.bootstrap.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginResponse;
import com.rag2agent.bootstrap.dto.AuthDtos.RegisterRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.UserView;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @author 21311
 */
@Service
public class AuthService {

    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, request.username())); //MP框架按用户名查询用户
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
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
