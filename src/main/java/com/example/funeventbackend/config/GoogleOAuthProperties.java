package com.example.funeventbackend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * app.oauth.google.* 的設定。
 *
 * <p>用 @ConfigurationProperties 而不是 @Value，是因為 allowedAudiences 是清單 ——
 * @Value 綁 YAML 的清單語法會很難看。
 *
 * @param clientId         網頁版的 client_id
 * @param clientSecret     ⚠️ 機密，只能待在 Spring，Next 不需要知道
 * @param allowedAudiences 可接受的 aud。⚠️ 是清單不是單一值：之後 App 版會有自己的
 *                         client_id，那時只要多加一行設定。但清單裡只能有「你自己的」client_id
 */
@ConfigurationProperties(prefix = "app.oauth.google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        List<String> allowedAudiences
) {
    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthProperties.class);

    /**
     * record 的 compact constructor —— 綁定完設定就會跑。
     *
     * <p>⚠️ 設定錯誤要在啟動時就炸，不要等到有人按下登入才收到一個空 body 的 401：
     * Google 對 invalid_client 是照 RFC 6749 回 401 + WWW-Authenticate 標頭，
     * body 是空的，從錯誤訊息完全看不出哪裡錯。
     */
    public GoogleOAuthProperties {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("app.oauth.google.client-id 未設定");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("app.oauth.google.client-secret 未設定");
        }
        // ⚠️ 從環境變數或 IDE 的 run configuration 複製貼上時，
        // 很容易夾帶前後空白或引號，而那會讓 Google 直接回 invalid_client
        if (!clientId.equals(clientId.trim()) || !clientSecret.equals(clientSecret.trim())) {
            throw new IllegalStateException(
                    "Google 的 client-id / client-secret 前後有空白，請檢查環境變數");
        }
        // 只印足以比對的前綴與長度，絕不印出 secret 本身
        log.info("Google OAuth 設定：clientId={}…（長度 {}）, clientSecret 長度={}",
                clientId.substring(0, Math.min(clientId.length(), 12)),
                clientId.length(),
                clientSecret.length());
    }
}
