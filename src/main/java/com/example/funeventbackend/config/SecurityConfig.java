package com.example.funeventbackend.config;

import com.example.funeventbackend.security.CustomUserDetailsService;
import com.example.funeventbackend.security.JwtAuthenticationEntryPoint;
import com.example.funeventbackend.security.JwtAuthenticationFilter;
import com.example.funeventbackend.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Security 設定。
 * <p>
 * 注意版本：Spring Boot 4.1 對應 <b>Spring Security 7.1</b>。
 * 網路上多數教學是 5.x / 6.x 時代的寫法（{@code WebSecurityConfigurerAdapter}、
 * {@code authorizeRequests()}、{@code antMatchers()}、鏈式 {@code .and()}），
 * 那些 API 都已經被移除，照抄會直接編譯失敗。
 * 這裡全部使用 lambda DSL —— 在 7.x 這不是風格偏好，而是唯一的選擇：
 * {@code HttpSecurity} 的每個設定方法都只接受 {@code Customizer}，沒有無參數多載。
 */
@Configuration
@EnableWebSecurity  // Boot 偵測到自訂 SecurityFilterChain 時會自動套用，這裡明寫是為了表達意圖
public class SecurityConfig {

    /**
     * 密碼雜湊器。註冊時用 {@code encode()} 雜湊，登入時用 {@code matches()} 比對。
     * <p>
     * BCrypt 的兩個關鍵性質：
     * 1. 自帶隨機 salt —— 同一組密碼每次 encode 結果都不同，擋掉彩虹表。
     * 2. 刻意設計成計算緩慢（預設 strength 10，約 2^10 次雜湊迭代），
     * 讓暴力破解的成本高到不划算。
     * <p>
     * 另外 {@code matches()} 內部是常數時間比對，不會因為「前幾個字元對了」
     * 而回應較慢，因此不會洩漏資訊給計時攻擊（timing attack）。
     * 這也是為什麼密碼比對絕對不能自己寫成 {@code hash.equals(input)}。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 全站唯一的 filter chain。
     * <p>
     * 曾經有第二條 {@code @Order(1)} 的 chain 專門服務 h2-console，因為 console
     * 需要 {@code csrf().disable()} 與 {@code frameOptions().sameOrigin()} 兩個
     * 「降低安全性」的設定，隔離在自己的 chain 裡才不會汙染 API。
     * 2026-08-28 轉入 PostgreSQL 後 console 一起移除，那條 chain 也就消失了 ——
     * 現在看資料庫內容是用 GUI 連 {@code localhost:5432}（DBeaver / pgAdmin 等）。
     */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            CustomUserDetailsService userDetailsService,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // JWT 放在 Authorization header，不會被瀏覽器自動夾帶，
                // 因此不存在 CSRF 的攻擊前提。改回 Cookie 的話這行必須拿掉。
                .csrf(csrf -> csrf.disable())

                // 無狀態：不建立也不使用 HttpSession。
                // 不設的話 Spring Security 會把 SecurityContext 存進 session，
                // 等於又變回有狀態，JWT 就失去意義了。
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // 註冊與登入本來就不可能帶 token
                        .requestMatchers("/api/auth/**").permitAll()
                        // 錯誤轉發到 /error 時也會經過授權檢查；
                        // 未登入的話真正的錯誤會被 401 蓋掉，很難查
                        .requestMatchers("/error").permitAll()

                        // 順序不能反：requestMatchers 照順序比對、第一個命中的生效，
                        // 這條較具體的規則必須排在下面那條 /api/events/** permitAll 之前
                        .requestMatchers(HttpMethod.GET,
                                "/api/events/*/comments/me",
                                "/api/events/*/comments/eligibility").authenticated()
                        // ⚠️ GET 這條不能少 —— 沒有它會被下面的 /api/events/** permitAll
                        // 蓋掉，未登入打進來 principal 是 null，controller 直接 NPE
                        .requestMatchers(HttpMethod.GET, "/api/events/*/favorite").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/events/*/favorite").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/events/*/favorite").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cities").permitAll()

                        // 金流商的伺服器不會帶我們的 JWT，只能開放。
                        // ⚠️ 這條路徑等於對全世界開放，安全性 100% 依賴
                        // PaymentGateway.parseCallback 的驗簽 —— 那裡不能有任何例外。
                        .requestMatchers(HttpMethod.POST, "/api/payments/callback").permitAll()
                        .anyRequest().authenticated())

                // 未登入時回 401 JSON。不設的話預設是 Http403ForbiddenEntryPoint，
                // 會回 403 —— 語意錯誤，前端無法據此判斷該不該跳轉登入頁
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint))

                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())

                // 掛上自己的 filter。刻意在這裡 new 而非用 @Component 注入，
                // 避免被 Spring Boot 自動註冊到 Tomcat filter 鏈而執行兩次。
                // 第二個參數只是「位置標記」，即使該 filter 不在 chain 裡也有效。
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
