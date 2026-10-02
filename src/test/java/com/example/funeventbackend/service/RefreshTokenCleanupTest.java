package com.example.funeventbackend.service;

import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * refresh token 清理排程。
 *
 * <p>⚠️ {@code RefreshTokenCleanupScheduler} 在測試環境是關掉的（見 application-test.yaml），
 * 所以這裡自己 new 一份，傳進去的 service 是 Spring 管理的真正 bean，
 * {@code @Transactional} 照常生效，只是觸發時間由測試控制。
 *
 * <p>⚠️ 不加 @Transactional：DELETE 與後續讀取要看到彼此真的提交的結果。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenCleanupTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private RefreshTokenCleanupScheduler scheduler;
    private User user;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        scheduler = new RefreshTokenCleanupScheduler(refreshTokenService, Duration.ofDays(1));
        user = userRepository.save(User.builder()
                .email("cleanup@example.com")
                .passwordHash("x")
                .name("清理測試")
                .role(RoleType.USER)
                .build());
    }

    private RefreshToken save(String hash, Instant expiresAt, boolean used, boolean revoked) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash)
                .familyId(UUID.randomUUID())
                .expiresAt(expiresAt)
                .used(used)
                .revoked(revoked)
                .build());
    }

    private boolean exists(String hash) {
        return refreshTokenRepository.findByTokenHash(hash).isPresent();
    }

    @Test
    @DisplayName("只刪過期超過緩衝期的：緩衝內與未過期的都留著")
    void deletesOnlyRowsExpiredBeyondRetention() {
        Instant now = Instant.now();
        save("expired-2-days", now.minus(2, ChronoUnit.DAYS), false, false);
        // ⚠️ 剛過期 1 小時，還在 1 天的緩衝內 —— 不能刪
        save("expired-1-hour", now.minus(1, ChronoUnit.HOURS), false, false);
        save("still-valid", now.plus(3, ChronoUnit.DAYS), false, false);

        scheduler.purgeExpired();

        assertTrue(!exists("expired-2-days"), "過期超過緩衝期的要刪掉");
        assertTrue(exists("expired-1-hour"), "過期但在緩衝期內的不該刪");
        assertTrue(exists("still-valid"), "還有效的不該刪");
    }

    @Test
    @DisplayName("⭐ 已使用但未過期的不能刪：清理之後竊用偵測仍然會撤銷整條 family")
    void usedButUnexpiredTokensSurviveSoTheftDetectionStillWorks() {
        // 一條真實的輪替鏈：first 已使用、second 是目前有效的那張
        String first = refreshTokenService.issueNewFamily(user);
        refreshTokenService.rotate(first);
        // 加一筆確定會被清掉的雜訊，證明清理真的有跑
        save("noise-expired", Instant.now().minus(3, ChronoUnit.DAYS), false, false);

        scheduler.purgeExpired();

        assertTrue(!exists("noise-expired"), "清理要真的有跑，否則這個測試什麼都沒證明");

        // 把 usedAt 往回撥，讓重放落在寬限期外 —— 作法同 RefreshTokenRotationTest
        List<RefreshToken> all = refreshTokenRepository.findAll();
        RefreshToken used = all.stream().filter(RefreshToken::isUsed).findFirst().orElseThrow();
        used.setUsedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(used);

        // ⭐ 舊票若被清掉，這裡會變成「查不到」：一樣回 Rejected，但整條 family 不會被撤銷。
        // 所以光斷言 Rejected 不夠，一定要斷言 family 全部 revoked
        assertInstanceOf(RotationOutcome.Rejected.class, refreshTokenService.rotate(first));
        List<RefreshToken> family = refreshTokenRepository.findByFamilyId(used.getFamilyId());
        assertEquals(2, family.size(), "輪替鏈上的兩張票都還在");
        assertTrue(family.stream().allMatch(RefreshToken::isRevoked),
                "竊用偵測要能撤銷整條 family");
    }

    @Test
    @DisplayName("已撤銷但未過期的先不刪：保留事後查證的紀錄")
    void revokedButUnexpiredTokensAreKept() {
        save("revoked-live", Instant.now().plus(2, ChronoUnit.DAYS), true, true);

        scheduler.purgeExpired();

        assertTrue(exists("revoked-live"));
    }

    @Test
    @DisplayName("重複執行是冪等的：第二次什麼都不刪，也不出錯")
    void runningTwiceIsHarmless() {
        save("old", Instant.now().minus(5, ChronoUnit.DAYS), false, false);
        save("live", Instant.now().plus(5, ChronoUnit.DAYS), false, false);

        scheduler.purgeExpired();
        scheduler.purgeExpired();

        assertEquals(1, refreshTokenRepository.count());
        assertTrue(exists("live"));
    }
}
