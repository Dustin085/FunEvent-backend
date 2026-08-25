package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.dto.user.ChangePasswordRequest;
import com.example.funeventbackend.dto.user.UpdateProfileRequest;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 會員中心。三支都需要登入 —— SecurityConfig 沒有把 /api/users/**
 * 放進 permitAll，所以落在 anyRequest().authenticated()。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/me")
    public UserResponse getCurrentUser(@AuthenticationPrincipal CustomUserDetails principal) {
        return userService.getCurrentUser(principal);
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return userService.updateProfile(principal.getUser(), request);
    }

    /**
     * 改密碼。
     *
     * <p>⚠️ 回傳的是 {@link AuthResponse}，裡面帶著新的一組 token ——
     * 舊的在後端已經全部撤銷了。BFF 必須把它們寫進 httpOnly cookie，
     * <b>不能</b>原封不動轉發給瀏覽器。
     */
    @PostMapping("/me/password")
    public AuthResponse changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return userService.changePassword(principal.getUser(), request);
    }
}
