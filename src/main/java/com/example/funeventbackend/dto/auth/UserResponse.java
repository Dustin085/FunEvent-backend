package com.example.funeventbackend.dto.auth;

import com.example.funeventbackend.model.RoleType;

public record UserResponse(
        Long id,
        String email,
        String name,
        RoleType role,
        /**
         * 這個帳號能不能用密碼登入。
         * ⚠️ 第三方登入建立的帳號沒有密碼 —— 前端要靠它決定顯示
         * 「修改密碼」表單還是「你是用 Google 登入的」說明
         */
        boolean hasPassword
) {
}
