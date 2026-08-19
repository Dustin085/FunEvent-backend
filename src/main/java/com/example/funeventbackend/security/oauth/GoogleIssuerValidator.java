package com.example.funeventbackend.security.oauth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * 檢查 iss 是 Google。
 *
 * <p>⚠️ 不能用 Spring 內建的 JwtIssuerValidator —— 那個只接受單一值，
 * 但 Google 的 ID Token 的 iss 有兩種寫法（歷史因素，兩種都是合法的），
 * Google 官方的驗證函式庫也是兩種都接受。
 */
public class GoogleIssuerValidator implements OAuth2TokenValidator<Jwt> {
    private static final Set<String> VALID_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
        if (VALID_ISSUERS.contains(issuer)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "ID Token 的 iss 不是 Google", null));
    }
}
