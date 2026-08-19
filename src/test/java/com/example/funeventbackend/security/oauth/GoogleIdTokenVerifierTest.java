package com.example.funeventbackend.security.oauth;

import com.example.funeventbackend.config.GoogleOAuthConfig;
import com.example.funeventbackend.exception.InvalidOAuthTokenException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ID Token 驗證的離線測試 —— 不需要 Google，也不需要網路。
 *
 * <p>做法：自己產一對 RSA 金鑰當作「Google 的金鑰」，用私鑰簽出各種 token，
 * 把公鑰餵給 decoder。⚠️ 驗證器用的是 GoogleOAuthConfig 裡正式環境同一組，
 * 只有公鑰來源不同 —— 否則測到的就不是真正跑的那套邏輯了。
 *
 * <p>對應接綠界時的 CheckMacValueCalculatorTest：把風險最高、
 * 最不容易用眼睛看出對錯的那一段，先用測試釘死。
 */
class GoogleIdTokenVerifierTest {
    private static final String CLIENT_ID = "our-client.apps.googleusercontent.com";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private RSAKey googleKey;
    private GoogleIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-key-1").generate();
        verifier = new GoogleIdTokenVerifier(decoderTrusting(googleKey));
    }

    @Test
    @DisplayName("正常的 ID Token 應該通過，並取出 sub / email / emailVerified")
    void validToken() throws Exception {
        String token = sign(googleKey, defaultClaims().build());

        GoogleIdTokenClaims claims = verifier.verify(token);

        assertThat(claims.sub()).isEqualTo("1234567890");
        assertThat(claims.email()).isEqualTo("someone@gmail.com");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.name()).isEqualTo("Some One");
    }

    @Test
    @DisplayName("⭐ aud 是別人的 client_id 時必須拒絕 —— 漏掉這關等於任何人都能接管帳號")
    void rejectsWrongAudience() throws Exception {
        // 攻擊者自己去 Google 申請的 client_id。token 本身完全合法：
        // 簽章對、iss 對、沒過期 —— 只有 aud 不是我們
        String token = sign(googleKey, defaultClaims()
                .audience("attacker-client.apps.googleusercontent.com")
                .build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    @DisplayName("過期的 token 必須拒絕")
    void rejectsExpiredToken() throws Exception {
        // ⚠️ 要退超過 60 秒：JwtTimestampValidator 預設有 60 秒的時鐘誤差容忍
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        String token = sign(googleKey, defaultClaims()
                .issueTime(Date.from(past.minus(10, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(past))
                .build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    @DisplayName("iss 不是 Google 時必須拒絕")
    void rejectsWrongIssuer() throws Exception {
        String token = sign(googleKey, defaultClaims()
                .issuer("https://accounts.evil.com")
                .build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    @DisplayName("用別把金鑰簽的 token 必須拒絕（偽造）")
    void rejectsTokenSignedByAnotherKey() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("google-key-1").generate();
        // 連 kid 都故意設成一樣，證明擋下來的是簽章驗證而不是 kid 比對
        String token = sign(attackerKey, defaultClaims().build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    @Test
    @DisplayName("payload 被抽換的 token 必須拒絕（簽章沒跟著重算）")
    void rejectsTamperedPayload() throws Exception {
        String legit = sign(googleKey, defaultClaims().build());
        String other = sign(googleKey, defaultClaims().subject("9999999999").build());

        // 取合法 token 的 header 與簽章，中間換成另一個人的 payload
        String[] legitParts = legit.split("\\.");
        String[] otherParts = other.split("\\.");
        String tampered = legitParts[0] + "." + otherParts[1] + "." + legitParts[2];

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(InvalidOAuthTokenException.class);
    }

    // ── 工具 ──────────────────────────────────────────────

    /** 用測試金鑰當公鑰來源，但驗證器沿用正式環境那一組 */
    private JwtDecoder decoderTrusting(RSAKey key) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(key.toRSAPublicKey())
                .build();
        decoder.setJwtValidator(
                GoogleOAuthConfig.googleIdTokenValidator(List.of(CLIENT_ID)));
        return decoder;
    }

    private JWTClaimsSet.Builder defaultClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(GOOGLE_ISSUER)
                .audience(CLIENT_ID)
                .subject("1234567890")
                .claim("email", "someone@gmail.com")
                .claim("email_verified", true)
                .claim("name", "Some One")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(10, ChronoUnit.MINUTES)));
    }

    private String sign(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
