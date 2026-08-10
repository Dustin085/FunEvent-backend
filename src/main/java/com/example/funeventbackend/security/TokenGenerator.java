package com.example.funeventbackend.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TokenGenerator {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String generateRawToken() {
        byte[] randomBytes = new byte[32];// 32 bytes，「隨機性」在電腦裡的自然單位是位元（bit）
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 保證一定支援的演算法（Java 規範明文規定），
            // 這個 catch 分支理論上永遠不會被執行——
            // 但 API 簽章上是 checked exception，還是得處理
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
