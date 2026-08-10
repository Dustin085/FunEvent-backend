package com.example.funeventbackend.service;

import com.example.funeventbackend.exception.InvalidRefreshTokenException;
import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.security.TokenGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final String INVALID_TOKEN_MESSAGE = "驗證失敗";
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenGenerator tokenGenerator;
    private final long expiration;
    private final RefreshTokenRevoker refreshTokenRevoker;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            TokenGenerator tokenGenerator,
            @Value("${app.refresh-token.expiration}") long expiration,
            RefreshTokenRevoker refreshTokenRevoker
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.expiration = expiration;
        this.refreshTokenRevoker = refreshTokenRevoker;
    }

    @Transactional
    public String issueNewFamily(User user) {
        // 新建 RefreshToken
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .familyId(UUID.randomUUID())
                .expiresAt(LocalDateTime.now().plus(expiration, ChronoUnit.MILLIS))
                .tokenHash(tokenHash)
                .build();
        // 存入資料庫
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        // 檢查 token 是否存在
        String tokenHash = tokenGenerator.hashToken(rawToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow(
                () -> new InvalidRefreshTokenException(INVALID_TOKEN_MESSAGE)
        );
        // 檢查 token 是否已被使用過(竊用)
        if (refreshToken.isUsed()) {
            // 應撤銷整個 family
            refreshTokenRevoker.revokeFamily(refreshToken.getFamilyId());
            throw new InvalidRefreshTokenException(INVALID_TOKEN_MESSAGE);
        }
        // 檢查 token 是否已過期
        if (LocalDateTime.now().isAfter(refreshToken.getExpiresAt())) {
            throw new InvalidRefreshTokenException(INVALID_TOKEN_MESSAGE);
        }
        // 修改舊 token(標記已使用)
        refreshToken.setUsed(true);
        // 建立新 token
        String newRawToken = tokenGenerator.generateRawToken();
        String newTokenHash = tokenGenerator.hashToken(newRawToken);
        RefreshToken newRefreshtoken = RefreshToken.builder()
                .user(refreshToken.getUser())
                .familyId(refreshToken.getFamilyId())
                // 每次換票重新建立七天有效期
                .expiresAt(LocalDateTime.now().plus(expiration, ChronoUnit.MILLIS))
                .tokenHash(newTokenHash)
                .build();
        // 存入資料庫
        // Hibernate dirty check 會自動 save(refreshToken)
        refreshTokenRepository.save(newRefreshtoken);
        // 回傳結果
        User user = newRefreshtoken.getUser();
        return new RotationResult(newRawToken, user);
    }

    // 登出使用
    @Transactional
    public void logout(String rawToken) {
        String tokenHash = tokenGenerator.hashToken(rawToken);
        // 查不到就當作已經登出了，不丟例外 —— 登出應該是等冪的
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            List<RefreshToken> family = refreshTokenRepository.findByFamilyId(token.getFamilyId());
            family.forEach(t -> t.setUsed(true));
        });
    }


    public record RotationResult(String rawToken, User user) {
    }
}
