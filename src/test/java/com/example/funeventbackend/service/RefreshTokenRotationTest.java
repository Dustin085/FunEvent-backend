package com.example.funeventbackend.service;

import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.TokenGenerator;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * refresh token 輪替的三條互斥分支。
 *
 * <p>⭐ 這個檔案存在的理由：使用者曾經回報「登入狀態偶爾自己斷掉，
 * 而且重新整理也救不回來」，追出來的原因就在 {@code rotate} 裡 ——
 * 已使用的 token 一被重放就撤銷整條 family，不可逆。
 * 而正常使用（多分頁、RSC 預抓取、Set-Cookie 沒送達）就會踩到。
 *
 * <p>分支選錯的後果是「使用者被登出」或「竊用沒被擋下」，
 * 兩種都不會在畫面上報錯，只能靠測試守。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenRotationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenGenerator tokenGenerator;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private User user;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        user = userRepository.save(User.builder()
                .email("rotation@example.com")
                .passwordHash("x")
                .name("換票測試")
                .role(RoleType.USER)
                .build());
    }

    @Test
    @DisplayName("正常輪替：換到新票，舊票標記已使用並記下時間")
    void rotateIssuesNewTokenAndMarksOldUsed() {
        String first = refreshTokenService.issueNewFamily(user);

        var result = rotated(first);

        assertNotEquals(first, result.rawToken(), "新票不能跟舊票一樣");
        assertEquals(user.getId(), result.user().getId());

        RefreshToken old = tokenOf(first);
        assertTrue(old.isUsed(), "舊票要標記已使用");
        // ⚠️ usedAt 是寬限期的起算點，沒寫進去的話之後所有重放都會被當成竊用
        assertNotNull(old.getUsedAt(), "舊票要記下使用時間");
        assertFalse(old.isRevoked(), "正常輪替不該撤銷任何東西");

        // 同一條 family，兩張票
        assertEquals(2, familyOf(first).size());
        assertEquals(old.getFamilyId(), tokenOf(result.rawToken()).getFamilyId());
    }

    @Test
    @DisplayName("寬限期內重放：拿到同一張票，不長出第二條分支")
    void replayWithinReuseIntervalReturnsSameToken() {
        String first = refreshTokenService.issueNewFamily(user);
        String second = rotated(first).rawToken();

        // 同一張舊票再送一次 —— 模擬多分頁或 Set-Cookie 沒送達瀏覽器
        String replayed = rotated(first).rawToken();

        // ⭐ 這是整個寬限期設計的重點：兩個請求拿到**同一張**票。
        // 若改成「再輪替一次」，雙方會各自長出一條鏈、都是未使用狀態，
        // 這條 family 從此不會再觸發竊用偵測，而且會留下一張沒人持有的孤兒票
        assertEquals(second, replayed, "寬限期內應該回傳前一個請求換到的那張");

        assertEquals(2, familyOf(first).size(), "不該產生第三張票");
        assertTrue(familyOf(first).stream().noneMatch(RefreshToken::isRevoked),
                "併發重送不是竊用，不該撤銷");
    }

    @Test
    @DisplayName("寬限期外重放：判定竊用，整條 family 撤銷")
    void replayAfterReuseIntervalRevokesFamily() {
        String first = refreshTokenService.issueNewFamily(user);
        refreshTokenService.rotate(first);

        // ⚠️ 用「把 usedAt 往回撥」而不是另開一個 reuse-interval=0 的 Spring context ——
        // 這樣三個案例可以待在同一個測試類別裡，而且直接測到 isWithinReuseInterval
        // 真正在比的那個值，不是繞過它
        RefreshToken old = tokenOf(first);
        old.setUsedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(old);

        assertInstanceOf(RefreshTokenService.RotationOutcome.Rejected.class,
                refreshTokenService.rotate(first));

        // ⭐ 撤銷必須真的落到資料庫。
        // rotate 之所以回傳結果而不是丟例外，就是為了這件事 ——
        // 丟例外會讓交易回滾、撤銷跟著不見。
        // 這條斷言守的是那個設計：改回丟例外的話這裡會紅
        assertTrue(familyOf(first).stream().allMatch(RefreshToken::isRevoked),
                "整條 family 都要被撤銷");
    }

    @Test
    @DisplayName("已撤銷的票一律拒絕，即使還在寬限期內")
    void revokedTokenIsRejectedEvenWithinReuseInterval() {
        String first = refreshTokenService.issueNewFamily(user);
        String second = rotated(first).rawToken();

        // 手動撤銷整條 family，模擬「別處已經判定竊用」
        List<RefreshToken> family = familyOf(first);
        family.forEach(token -> token.setRevoked(true));
        refreshTokenRepository.saveAll(family);

        // ⚠️ first 的 usedAt 還在寬限期內。revoked 的檢查若排在寬限期之後，
        // 這裡會通過寬限期、從快取拿到 second 回傳 ——
        // 等於在一條已判定遭竊的 family 上繼續發票，撤銷白做
        assertInstanceOf(RefreshTokenService.RotationOutcome.Rejected.class,
                refreshTokenService.rotate(first));
        assertInstanceOf(RefreshTokenService.RotationOutcome.Rejected.class,
                refreshTokenService.rotate(second));
    }

    /** 期待換票成功，順便斷言型別 —— 讓每個案例不必自己 cast */
    private RefreshTokenService.RotationOutcome.Rotated rotated(String rawToken) {
        return assertInstanceOf(RefreshTokenService.RotationOutcome.Rotated.class,
                refreshTokenService.rotate(rawToken), "這一步應該要換票成功");
    }

    private RefreshToken tokenOf(String rawToken) {
        return refreshTokenRepository.findByTokenHash(tokenGenerator.hashToken(rawToken))
                .orElseThrow(() -> new AssertionError("找不到 token"));
    }

    private List<RefreshToken> familyOf(String rawToken) {
        return refreshTokenRepository.findByFamilyId(tokenOf(rawToken).getFamilyId());
    }
}
