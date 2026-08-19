package com.example.funeventbackend.config;

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
}
