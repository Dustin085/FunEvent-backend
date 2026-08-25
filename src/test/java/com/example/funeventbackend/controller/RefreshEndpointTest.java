package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.RefreshToken;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.TokenGenerator;
import com.example.funeventbackend.service.RefreshTokenService;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/auth/refresh 的端到端行為。
 *
 * <p>⭐ 這個檔案真正在守的是一個**分層安排**：
 * {@code rotate} 與 {@code UserService.refresh} 都不丟例外（它們在交易裡，
 * 丟例外會回滾掉竊用撤銷），例外由沒有交易的 Controller 丟。
 *
 * <p>把 {@code orElseThrow} 往 Service 搬會讓下面「撤銷要存活」那條斷言變紅 ——
 * 而那個錯誤在畫面上完全看不出來：使用者一樣看到 401，
 * 只是被偷走的 token 從此不會再被撤銷。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshEndpointTest {

    @Autowired
    private MockMvc mockMvc;
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
                .email("refresh-endpoint@example.com")
                .passwordHash("x")
                .name("換票端點測試")
                .role(RoleType.USER)
                .build());
    }

    @Test
    @DisplayName("有效的 refresh token 換到新的一組")
    void validTokenReturnsNewPair() throws Exception {
        String rawToken = refreshTokenService.issueNewFamily(user);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.email").value("refresh-endpoint@example.com"));
    }

    @Test
    @DisplayName("認不得的 refresh token 回 401")
    void unknownTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"這張票不存在\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⭐ 竊用被判定時：回 401，而且撤銷真的落到資料庫")
    void replayAfterReuseIntervalReturns401AndRevocationSurvives() throws Exception {
        String first = refreshTokenService.issueNewFamily(user);
        refreshTokenService.rotate(first);

        // 把 usedAt 往回撥，讓重放落在寬限期外 → 判定竊用
        RefreshToken old = refreshTokenRepository
                .findByTokenHash(tokenGenerator.hashToken(first)).orElseThrow();
        old.setUsedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(old);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first + "\"}"))
                .andExpect(status().isUnauthorized());

        // ⚠️ 這條是重點：401 只證明請求被拒絕，不證明撤銷有存活。
        // 例外若在交易內丟出，這裡全部會是 false，而 HTTP 狀態碼一模一樣
        assertTrue(refreshTokenRepository.findByFamilyId(old.getFamilyId())
                        .stream().allMatch(RefreshToken::isRevoked),
                "整條 family 的撤銷必須跨過 HTTP 回應存活下來");
    }
}
