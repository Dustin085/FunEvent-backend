package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Email 不可為空")
        @Email(message = "Email 格式錯誤")
        String email,

        @NotBlank(message = "密碼不可為空")
        String password
) {
}
