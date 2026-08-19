package com.example.funeventbackend.security.oauth;

import com.example.funeventbackend.exception.InvalidOAuthTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * 驗證 Google 的 ID Token 並取出我們需要的欄位。
 *
 * <p>實際的檢查項目由注入的 decoder 上掛的驗證器決定，見 GoogleOAuthConfig：
 * 簽章（JWKS 公鑰）、exp、iss、aud。
 *
 * <p>⚠️ 沒有檢查 nonce。OIDC 規範裡 nonce 對 implicit / hybrid flow 是必要的，
 * 對 authorization code flow 是選用的 —— 我們用的是 code flow 加 PKCE，
 * code 已經被綁定在發起流程的那個 session 上了，nonce 能多擋的有限。
 * 這是刻意的取捨，不是遺漏。
 */
@Component
@Slf4j
public class GoogleIdTokenVerifier {
    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(@Qualifier("googleIdTokenDecoder") JwtDecoder decoder) {
        this.decoder = decoder;
    }

    public GoogleIdTokenClaims verify(String idToken) {
        Jwt jwt;
        try {
            // ⚠️ 這一行不只是解碼 —— decode() 內部依序做：
            // 解析 → 拒絕 alg:none → 驗簽章（JWKS 公鑰）→ 跑驗證器（exp/iss/aud）。
            // 驗證器是 GoogleOAuthConfig 用 setJwtValidator 掛上去的，
            // 所以這裡看不到，但 aud 那一關就是在這行被擋下來的
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            // ⚠️ 不要把 e.getMessage() 回給前端 —— 它會明講是哪一項驗證失敗，
            // 等於告訴攻擊者「你只差 aud 沒對」。log 留完整訊息給我們自己看
            log.warn("Google ID Token 驗證失敗", e);
            throw new InvalidOAuthTokenException("第三方登入憑證無效");
        }

        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        String email = jwt.getClaimAsString("email");
        if (sub == null || email == null) {
            // 要求了 scope=openid email 就一定會有這兩個。沒有代表流程哪裡出錯了
            log.warn("Google ID Token 缺少必要欄位 sub={} email={}", sub, email);
            throw new InvalidOAuthTokenException("第三方登入憑證無效");
        }

        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        return new GoogleIdTokenClaims(
                sub,
                email,
                // 沒帶就當成未驗證 —— 往嚴格的方向預設
                Boolean.TRUE.equals(emailVerified),
                jwt.getClaimAsString("name"));
    }
}
