package com.example.funeventbackend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        // ⚠️ 刻意不加 @Size —— 這個欄位是拿去比對舊 hash 的，不是要設定的新值。
        // 加了的話，密碼規則變嚴之前註冊的老帳號會連改密碼都做不到
        @NotBlank(message = "請輸入目前的密碼")
        String currentPassword,

        @NotBlank(message = "新密碼不可為空")
        @Size(min = 8, message = "密碼至少 8 個字元")
        String newPassword
) {
}
