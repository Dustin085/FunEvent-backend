package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email 不可為空")
        @Email(message = "Email 格式錯誤")
        String email,

        @NotBlank(message = "密碼不可為空")
        @Size(min = 8, message = "密碼至少 8 個字元")
        String password,

        @NotBlank(message = "名字不可為空")
        @Size(max = 50, message = "名字長度不可超過 50 字元")
        String name
) {
}
