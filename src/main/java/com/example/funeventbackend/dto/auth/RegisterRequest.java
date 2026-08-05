package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email 不可為空")
        @Email
        String email,

        @NotBlank(message = "密碼不可為空")
        @Size(min = 6, message = "密碼至少 6 個字元")
        String password,

        @NotBlank
        @Size(max = 50, message = "名字長度不可超過 50 字元")
        String name
) {
}
