package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/me")
    public UserResponse getCurrentUser(@AuthenticationPrincipal CustomUserDetails principal) {
        return userService.getCurrentUser(principal);
    }
}
