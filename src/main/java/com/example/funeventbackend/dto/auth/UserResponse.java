package com.example.funeventbackend.dto.auth;

public record UserResponse(
        Long id,
        String email,
        String name
) {
}
