package com.example.funeventbackend.security.oauth;

import com.example.funeventbackend.config.GoogleOAuthProperties;
import com.example.funeventbackend.exception.InvalidOAuthTokenException;
import com.example.funeventbackend.exception.OAuthProviderUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OAuth 流程的第 8、9 步：拿 code 去跟 Google 換 token。
 *
 * <p>⚠️ 這是整個流程裡唯一的「後端對後端」請求 —— code 是走瀏覽器網址列回來的，
 * 但真正的 token 只在這個請求裡傳輸，從頭到尾不經過瀏覽器。
 * client_secret 也只出現在這裡。
 */
@Component
@Slf4j
public class GoogleTokenExchanger {
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    private final RestClient restClient;
    private final GoogleOAuthProperties properties;

    public GoogleTokenExchanger(
            @Qualifier("googleOAuthRestClient") RestClient restClient,
            GoogleOAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public String exchangeCodeForIdToken(String code, String codeVerifier, String redirectUri) {
        // OAuth 的 token 端點吃的是表單編碼，不是 JSON
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        GoogleTokenResponse response;
        try {
            response = restClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException e) {
            // ⚠️ 只記回應，不要記 form —— 那裡面有 client_secret
            log.warn("Google token 兌換失敗 status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                // Google 明確拒絕：code 過期／已用過／verifier 對不上／redirect_uri 不符
                throw new InvalidOAuthTokenException("第三方登入憑證無效");
            }
            throw new OAuthProviderUnavailableException("第三方登入服務暫時無法使用，請稍後再試");
        } catch (RestClientException e) {
            // 連不上、逾時、回應解析不了 —— 不是使用者的問題
            log.error("無法連線到 Google token 端點", e);
            throw new OAuthProviderUnavailableException("第三方登入服務暫時無法使用，請稍後再試");
        }

        if (response == null || response.idToken() == null) {
            log.error("Google token 回應缺少 id_token（scope 是不是漏了 openid？）");
            throw new OAuthProviderUnavailableException("第三方登入服務回應異常，請稍後再試");
        }
        return response.idToken();
    }

    /**
     * 只取 id_token —— 純登入不需要 access_token（那是拿去打 Google API 的）。
     * ⚠️ 明確標 ignoreUnknown：Google 還會回 access_token / expires_in / scope 等欄位，
     * 不想依賴全域 ObjectMapper 的設定剛好是寬鬆的
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleTokenResponse(@JsonProperty("id_token") String idToken) {
    }
}
