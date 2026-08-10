package com.example.funeventbackend.service;

import com.example.funeventbackend.exception.EmailSendException;
import com.example.funeventbackend.exception.InvalidResetTokenException;
import com.example.funeventbackend.model.PasswordResetToken;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.PasswordResetTokenRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.TokenGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class PasswordResetService {
    private static final String INVALID_TOKEN_MESSAGE = "重設連結無效或已過期";
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String frontendUrl;
    private final TokenGenerator tokenGenerator;

    public PasswordResetService(
            EmailService emailService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.frontend-url}") String frontendUrl,
            TokenGenerator tokenGenerator) {
        this.emailService = emailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.frontendUrl = frontendUrl;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public void requestReset(String email) {
        // 檢查是否有這個使用者
        Optional<User> optionalUser = userRepository.findByEmail(email);
        // 如果 email 根本沒有被註冊過就直接返回，不拋例外以免洩漏註冊情況
        if (optionalUser.isEmpty()) {
            return;
        }
        User user = optionalUser.get();
        // 檢查是否已有可用 token
        Optional<PasswordResetToken> optionalValidToken =
                passwordResetTokenRepository
                        .findByUserAndUsedFalseAndExpiresAtAfter(user, LocalDateTime.now());
        // 已有有效 token -> 作廢舊的 TODO 做 rate limiting 防止寄信被濫用
        optionalValidToken.ifPresent(old -> {
            old.setUsed(true);
            passwordResetTokenRepository.save(old);
            // 之後正常往下產生新的 token
        });
        // 產生 token，hash token
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenGenerator.hashToken(rawToken);
        // 存資料庫
        int expirationMin = 15;
        PasswordResetToken passwordResetToken = PasswordResetToken.builder()
                .tokenHash(tokenHash)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMin))
                .build();
        passwordResetTokenRepository.save(passwordResetToken);
        // 寄出信件
        String resetLink = frontendUrl + "/reset-password?token=" + rawToken;

        try {
            emailService.sendEmail(
                    email,
                    "重設密碼",
                    "使用這個網址來重設密碼: " + resetLink
            );
        } catch (EmailSendException e) {
            log.error("重設密碼信寄送失敗 email={}", email, e);
            // 不讓 EmailSendException 拋到 Controller
            // 防列舉測 email 是否註冊
        }
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        // 驗證拿到的 rawToken hash 後在資料庫裡面是否有相應的 row
        PasswordResetToken passwordResetToken = passwordResetTokenRepository
                .findByTokenHash(tokenGenerator.hashToken(rawToken))
                .orElseThrow(() -> new InvalidResetTokenException(INVALID_TOKEN_MESSAGE));
        // 驗證是否過期
        if (LocalDateTime.now().isAfter(passwordResetToken.getExpiresAt())) {
            throw new InvalidResetTokenException(INVALID_TOKEN_MESSAGE);
        }
        // 驗證是否使用過
        if (passwordResetToken.isUsed()) {
            throw new InvalidResetTokenException(INVALID_TOKEN_MESSAGE);
        }

        // 重新設定密碼
        User user = passwordResetToken.getUser();
        String passwordHash = passwordEncoder.encode(newPassword);
        user.setPasswordHash(passwordHash);
        // token 設定成 used = true
        passwordResetToken.setUsed(true);
        // 存入資料庫
        passwordResetTokenRepository.save(passwordResetToken);
        userRepository.save(user);
    }
}
