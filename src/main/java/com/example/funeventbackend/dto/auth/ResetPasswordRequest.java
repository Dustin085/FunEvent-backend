package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "缺少必要欄位")
        String token,

        @NotBlank(message = "密碼不可為空")
        @Size(min = 8, message = "密碼至少 8 個字元")
        String newPassword
) {
}
