package com.example.funeventbackend.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Next 的 callback Route Handler 送過來的東西。
 *
 * @param code         Google 轉址帶回來的授權碼
 * @param codeVerifier PKCE 的原文。⚠️ 是 Next 產生並存在自己的 cookie 裡的 ——
 *                     兌換的人是 Spring，所以要跟著送過來
 * @param redirectUri  ⚠️ 必須和當初送給 Google 的一字不差，否則 Google 會回 invalid_grant。
 *                     由 Next 提供而不是寫在 Spring 的設定裡：授權網址是 Next 組的，
 *                     它才是這個值的唯一真實來源。讓客戶端指定看似有風險，
 *                     但 Google 會拿它跟「已註冊的轉址 URI 清單」比對，填別的一律拒絕
 */
public record GoogleOAuthLoginRequest(
        @NotBlank(message = "code 不可為空")
        String code,

        @NotBlank(message = "codeVerifier 不可為空")
        String codeVerifier,

        @NotBlank(message = "redirectUri 不可為空")
        String redirectUri
) {
}
