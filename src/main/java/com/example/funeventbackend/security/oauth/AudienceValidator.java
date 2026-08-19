package com.example.funeventbackend.security.oauth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * 檢查 ID Token 的 aud 是不是發給「我們」的。
 *
 * <p>⚠️ 這是整個 OAuth 流程裡唯一「漏了會被接管帳號」的檢查。
 *
 * <p>任何人都能去 Google 申請一個 client_id，然後讓使用者在他的網站登入，
 * 拿到一個**簽章完全合法、iss 正確、沒過期**的 ID Token。
 * 如果我們不檢查 aud，他把那個 token 送過來，我們就會認為
 * 「Google 說這個人是 sub=1234」而讓他登入受害者的帳號。
 *
 * <p>簽章只證明「這是 Google 發的」，aud 才證明「這是發給我的」。
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final List<String> allowedAudiences;

    public AudienceValidator(List<String> allowedAudiences) {
        if (allowedAudiences == null || allowedAudiences.isEmpty()) {
            // 空清單會讓下面的 stream 永遠回 false，變成「全部拒絕」——
            // 那是安全的失敗方向，但會很難查。設定漏了就直接不要啟動
            throw new IllegalArgumentException("app.oauth.google.allowed-audiences 不可為空");
        }
        this.allowedAudiences = List.copyOf(allowedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // aud 在規範裡是陣列，只要其中一個是我們的就算通過
        boolean matched = token.getAudience().stream().anyMatch(allowedAudiences::contains);
        if (matched) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "ID Token 的 aud 不是本站的 client_id",
                null));
    }
}
