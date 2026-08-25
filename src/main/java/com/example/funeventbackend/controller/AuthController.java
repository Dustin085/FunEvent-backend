package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.MessageResponse;
import com.example.funeventbackend.dto.auth.*;
import com.example.funeventbackend.exception.InvalidRefreshTokenException;
import com.example.funeventbackend.service.OAuthLoginService;
import com.example.funeventbackend.service.PasswordResetService;
import com.example.funeventbackend.service.RefreshTokenService;
import com.example.funeventbackend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final RefreshTokenService refreshTokenService;
    private final OAuthLoginService oAuthLoginService;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    /**
     * 第三方登入（網頁版）。由 Next 的 callback Route Handler 呼叫，
     * 不是瀏覽器直接打的 —— 瀏覽器只會走到 Next 那一層。
     *
     * <p>SecurityConfig 的 /api/auth/** 已經是 permitAll：
     * 這支端點本來就是給還沒有身分的人用的。
     */
    @PostMapping("/oauth/google")
    public ResponseEntity<AuthResponse> loginWithGoogle(
            @Valid @RequestBody GoogleOAuthLoginRequest request) {
        return ResponseEntity.ok(oAuthLoginService.loginWithGoogleCode(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.logout(request.refreshToken());
        return ResponseEntity.ok(new MessageResponse("已登出"));
    }

    /**
     * ⚠️ 例外要在這裡丟，<b>不能</b>往 Service 裡搬：Controller 這一層沒有交易，
     * 到得了這裡就代表 rotate 的竊用撤銷已經提交了。
     * 搬進 UserService.refresh（它是 @Transactional）會讓那個撤銷被回滾。
     * 見 RefreshTokenService.RotationOutcome 的說明。
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(userService.refresh(request.refreshToken())
                .orElseThrow(() -> new InvalidRefreshTokenException("驗證失敗")));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse>  forgotPassword(@Valid @RequestBody ForgetPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.ok(new MessageResponse("如果此信箱已註冊，我們已寄出重設密碼信"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse>  resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("密碼重設成功，請重新登入"));
    }
}
