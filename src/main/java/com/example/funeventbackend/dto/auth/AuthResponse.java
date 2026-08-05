package com.example.funeventbackend.dto.auth;

import com.example.funeventbackend.model.RoleType;

public record AuthResponse(
        Long id,
        String email,
        String name,
        RoleType role,
        String token
) {
}
