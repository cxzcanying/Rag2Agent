package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.entity.AppUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空")
                    @Size(min = 6, max = 32, message = "密码长度需在 6-32 之间")
                    String password,
            String nickname) {}

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {}

    public record LoginResponse(String token, UserView user) {}

    public record UserView(Long id, String username, String nickname, Instant createdAt) {
        public static UserView from(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getCreatedAt());
        }
    }
}
