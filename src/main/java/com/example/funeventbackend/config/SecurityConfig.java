package com.example.funeventbackend.config;

import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
     * 【第一條 chain】只負責 H2 Console。
     * <p>
     * 為什麼要獨立一條而不是全部寫在一起？因為 h2-console 需要兩個
     * 「降低安全性」的設定，把它們限制在這條 chain 裡，就不會汙染到 API。
     * 之後階段 C 改寫 API 的安全規則時，也完全不需要動到這裡。
     * <p>
     * {@code securityMatcher} vs {@code requestMatchers} 的差別（很容易搞混）：
     * <ul>
     *   <li>{@code securityMatcher} —— 決定「這條 chain 要不要接手這個請求」。
     *       沒被選中的請求會往下一條 chain 找。</li>
     *   <li>{@code requestMatchers} —— chain 接手之後，決定「這個路徑需要什麼權限」。</li>
     * </ul>
     */
    @Bean
    @Order(1)  // 數字小的先比對，必須排在下面那條 anyRequest 的 chain 前面
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // PathRequest.toH2Console() 會自動讀取 application.yaml 的
                // spring.h2.console.path，所以之後改路徑這裡不用跟著改。
                // 注意套件路徑：Boot 4 把模組拆開後，這個類別從
                // org.springframework.boot.autoconfigure.security.servlet
                // 搬到了 org.springframework.boot.security.autoconfigure.web.servlet
                .securityMatcher(PathRequest.toH2Console())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())

                // H2 Console 的表單沒有帶 Spring Security 的 CSRF token，
                // 不關掉的話所有操作都會被擋成 403。
                .csrf(csrf -> csrf.disable())

                // H2 Console 的畫面是用 frameset / iframe 排版的。
                // Spring Security 預設送出 X-Frame-Options: DENY 來防止點擊劫持
                // (clickjacking)，會導致 console 開起來一片空白。
                // sameOrigin 允許同源頁面內嵌，剛好夠 console 使用。
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin()));

        return http.build();
    }

    /**
     * 【第二條 chain】其餘所有請求。
     * <p>
     * ⚠ 目前是<b>暫時的全放行</b>設定，只為了讓階段 B 能專心把註冊／登入的
     * 流程跑通，不要一開始就被 401 卡住。
     * <p>
     * 階段 C 加入 JWT 時，這條 chain 會改成：
     * <pre>
     *   .sessionManagement(s -&gt; s.sessionCreationPolicy(STATELESS))
     *   .authorizeHttpRequests(auth -&gt; auth
     *           .requestMatchers("/api/auth/**").permitAll()
     *           .anyRequest().authenticated())
     *   .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
     * </pre>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // 這是純 API 後端，之後用 JWT 而非 Cookie 傳遞身分。
                // CSRF 攻擊的前提是瀏覽器會自動夾帶 Cookie；JWT 放在
                // Authorization header 需要 JS 主動附加，不會被自動送出，
                // 因此關掉 CSRF 是合理的。
                // 但若哪天改回 Cookie/Session，這行就必須拿掉。
                .csrf(csrf -> csrf.disable())

                // TODO 階段 C：改成 /api/auth/** 放行、其餘 authenticated()
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())

                // 關掉預設的登入表單與 HTTP Basic。
                // 不關的話，未授權的請求會被導向 HTML 登入頁或跳出瀏覽器彈窗，
                // 但 API 應該回傳 401 讓前端自己處理。
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable());

        return http.build();
    }
}
