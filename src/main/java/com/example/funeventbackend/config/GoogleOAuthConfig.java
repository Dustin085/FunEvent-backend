package com.example.funeventbackend.config;

import com.example.funeventbackend.security.oauth.AudienceValidator;
import com.example.funeventbackend.security.oauth.GoogleIssuerValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthConfig {
    /** Google 的公鑰集合。金鑰會定期輪替，NimbusJwtDecoder 會自己快取與重抓 */
    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /**
     * ⚠️ Bean 取名 googleIdTokenDecoder 而不是 jwtDecoder：
     * 我們自己發的 access token 是用 jjwt 驗的（見 JwtService / JwtAuthenticationFilter），
     * 兩者完全無關。取一般化的名字遲早會有人注入錯。
     */
    @Bean
    public JwtDecoder googleIdTokenDecoder(GoogleOAuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(GOOGLE_JWK_SET_URI)
                // 只接受 RS256。不限制的話等於接受 decoder 支援的任何演算法
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(googleIdTokenValidator(properties.allowedAudiences()));
        return decoder;
    }

    /**
     * 專門打 Google token 端點用的 RestClient。
     *
     * <p>⚠️ 自己建而不是注入自動組態的 RestClient.Builder：
     * Boot 4 把 RestClientAutoConfiguration 移到 spring-boot-restclient 模組，
     * 我們的 classpath 上沒有它（spring-boot-starter-webmvc 不含）。
     *
     * <p>順帶好處是逾時可以只針對這個用途設定 —— 這是登入路徑上的對外請求，
     * 沒有逾時的話 Google 一慢就會把執行緒卡住。
     */
    @Bean
    public RestClient googleOAuthRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * ⚠️ setJwtValidator 是「取代」預設驗證器，不是「附加」——
     * 所以 JwtTimestampValidator（exp 檢查）必須自己列進來，漏了就變成不檢查過期。
     *
     * <p>public static 是為了讓測試能用「和正式環境完全同一組」的驗證器，
     * 只把公鑰來源換成測試金鑰。
     */
    public static OAuth2TokenValidator<Jwt> googleIdTokenValidator(List<String> allowedAudiences) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new GoogleIssuerValidator(),
                new AudienceValidator(allowedAudiences));
    }
}
