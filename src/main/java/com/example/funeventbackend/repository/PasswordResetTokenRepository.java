package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.PasswordResetToken;
import com.example.funeventbackend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    // 用 tokenHash 找 PasswordResetToken
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    // 查這個使用者「還有效」的 token（沒用過、沒過期）
    // 用在申請重設時，防止短時間內被狂刷寄信
    Optional<PasswordResetToken> findByUserAndUsedFalseAndExpiresAtAfter(
            User user, Instant now);
}
