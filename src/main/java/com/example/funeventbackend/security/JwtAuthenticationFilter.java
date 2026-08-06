package com.example.funeventbackend.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 從 Authorization header 取出 JWT，驗證後把身分放進 SecurityContext。
 * <p>
 * ⚠ 這個 filter <b>不做授權決定</b>。它只負責「確認你是誰」，
 * 「能不能進來」是 chain 尾端的 AuthorizationFilter 依照 SecurityConfig
 * 的規則決定的。所以這裡任何情況都不會回傳 401 或中斷請求。
 * <p>
 * ⚠ 刻意<b>不</b>標 {@code @Component}：標了會被 Spring Boot 自動註冊到
 * Tomcat 的 servlet filter 鏈，加上 SecurityConfig 裡的 addFilterBefore
 * 會導致同一個 filter 執行兩次。改由 SecurityConfig 直接 new 出來。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";   // 注意結尾的空格

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        tryAuthenticate(request);

        // 無論上面成功與否，一律放行往下一個 filter。
        // 漏掉這行 = 請求憑空消失，而且 log 不會有任何線索。
        filterChain.doFilter(request, response);
    }

    /**
     * 嘗試建立身分。任何一步不成立就直接返回，讓請求以「匿名」狀態往下走。
     */
    private void tryAuthenticate(HttpServletRequest request) {
        // 已經有身分就不覆蓋（可能由其他機制建立）
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            return;   // 沒帶 token 是正常情況，例如 /api/auth/login
        }

        Optional<Claims> claims = jwtService.parseToken(token);
        if (claims.isEmpty()) {
            return;   // 過期、簽章錯、格式壞 —— JwtService 內部已經 log.debug 了
        }

        String email = claims.get().getSubject();
        if (email == null) {
            return;
        }

        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            // authenticated(...) 靜態工廠：明確表達「這是已通過驗證的身分」。
            // credentials 傳 null —— 密碼在登入時就驗過了，這裡沒有也不需要。
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            userDetails,
                            null,
                            userDetails.getAuthorities());

            // 附上 IP、session id 等請求細節，日後稽核或 log 用得到
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT 驗證成功: {}", email);

        } catch (UsernameNotFoundException e) {
            // token 本身有效，但使用者已被刪除 —— 不建立身分，請求以匿名往下走
            log.debug("token 有效但查無使用者: {}", email);
        }
    }

    /**
     * @return token 字串，或 null（沒有 header／格式不符）
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || !header.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        return header.substring(TOKEN_PREFIX.length());
    }
}