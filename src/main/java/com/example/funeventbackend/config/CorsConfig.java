package com.example.funeventbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * CORS 設定。
 * <p>
 * 這是<b>放寬</b>瀏覽器同源政策的宣告，不是一種保護機制 ——
 * 它在告訴瀏覽器「我允許這些來源的 JS 讀取我的回應」。
 * <p>
 * ⚠ 只有瀏覽器受同源政策約束。Postman、curl、其他後端服務完全不受影響，
 * 所以這裡設錯了用 Postman 是測不出來的。
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 允許的前端來源。必須是完整的 scheme://host:port，
        // 結尾不能有斜線，否則字串比對不會過。
        config.setAllowedOrigins(allowedOrigins);

        // 允許的 HTTP 方法。OPTIONS 不用列 —— preflight 由 CorsFilter
        // 直接處理掉，不會走到授權那一關。
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));

        // 允許前端送出的 request header。
        // 你至少需要 Authorization（帶 JWT）和 Content-Type（送 JSON）。
        config.setAllowedHeaders(List.of("*"));

        // 允許前端 JS「讀取」的 response header。
        // 預設只有幾個基本 header 讀得到，其餘一律被瀏覽器藏起來。
        // 之後若要在 header 回傳分頁總數之類的資訊，要加在這裡。
        config.setExposedHeaders(List.of("Authorization"));

        // ⚠ 刻意不開啟 setAllowCredentials(true)。
        // credentials 指的是 cookie 和 HTTP 認證，會被瀏覽器「自動夾帶」。
        // 你的 JWT 是由 JS 主動放進 Authorization header 的，不屬於這一類，
        // 所以不需要開。
        // 之後若改用 httpOnly cookie 存 token，這裡要改成 true，
        // 且 allowedOrigins 不能再用萬用字元。

        // preflight 的結果可以被瀏覽器快取多久。
        // 沒設的話每個請求前都要多跑一次 OPTIONS，開發時很吵。
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 只套用在 /api/** —— 其餘路徑（錯誤頁、actuator 之類）不對外開放跨來源存取
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}