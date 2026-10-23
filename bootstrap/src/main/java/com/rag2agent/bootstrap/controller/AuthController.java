package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.config.RegistrationProperties;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.LoginResponse;
import com.rag2agent.bootstrap.dto.AuthDtos.RegisterRequest;
import com.rag2agent.bootstrap.dto.AuthDtos.UserView;
import com.rag2agent.bootstrap.service.AuthService;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RegistrationProperties registrationProperties;

    public AuthController(AuthService authService, RegistrationProperties registrationProperties) {
        this.authService = authService;
        this.registrationProperties = registrationProperties;
    }

    @GetMapping("/registration-options")
    public ApiResponse<Map<String, Boolean>> registrationOptions() {
        boolean enabled = registrationProperties.getMode() == RegistrationProperties.Mode.INVITE;
        return ApiResponse.success(Map.of(
                "registrationEnabled", enabled,
                "inviteRequired", enabled));
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@RequestBody @Valid RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.success(authService.me(StpUtil.getLoginIdAsLong()));
    }
}
