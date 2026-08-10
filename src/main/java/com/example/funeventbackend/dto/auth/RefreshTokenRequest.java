package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message = "缺少 refresh token")
        String refreshToken
) {
}
