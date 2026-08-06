package com.example.funeventbackend.security;

import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "test-secret-key-for-unit-test-1234567890";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3600000L);

        user = User.builder()
                .id(1L)
                .email("alex@example.com")
                .passwordHash("$2a$10$dummyHashValue")
                .name("Alex")
                .role(RoleType.USER)
                .build();
    }

    @Test
    void parseToken() {
        String token = jwtService.generateToken(user);
        Optional<Claims> result = jwtService.parseToken(token);

        assertTrue(result.isPresent(), "有效 token 應解析成功");
        Claims claims = result.get();
        assertEquals("alex@example.com", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
    }
}