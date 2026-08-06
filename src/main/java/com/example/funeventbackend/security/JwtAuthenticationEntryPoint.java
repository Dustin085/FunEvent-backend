package com.example.funeventbackend.security;

import com.example.funeventbackend.dto.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;   // ⚠ Boot 4 是 Jackson 3，不是 com.fasterxml

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未登入時的回應。由 ExceptionTranslationFilter 呼叫。
 * <p>
 * ⚠ 為什麼不能靠 GlobalExceptionHandler：這裡還在 Spring Security 的
 * filter chain 內部，請求<b>根本還沒到達 DispatcherServlet</b>，
 * {@code @RestControllerAdvice} 是 Spring MVC 層的機制，完全不會被觸發。
 * 所以回應必須自己寫進 HttpServletResponse。
 * <p>
 * 標 {@code @Component} 是安全的 —— 它不是 servlet Filter，
 * 不會有 JwtAuthenticationFilter 那種被自動註冊兩次的問題。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         @NonNull AuthenticationException authException) throws IOException {

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED,
                "請先登入",
                request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}