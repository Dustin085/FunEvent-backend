package com.example.funeventbackend.dto.auth;

import com.example.funeventbackend.model.RoleType;

public record UserResponse(
        Long id,
        String email,
        String name,
        RoleType role
) {
}
