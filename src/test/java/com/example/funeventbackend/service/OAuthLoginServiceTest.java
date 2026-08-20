package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.LoginRequest;
import com.example.funeventbackend.exception.OAuthAccountLinkConflictException;
import com.example.funeventbackend.exception.OAuthOnlyAccountException;
import com.example.funeventbackend.model.OAuthProvider;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.support.DatabaseCleaner;
import com.example.funeventbackend.repository.UserOAuthAccountRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.oauth.GoogleIdTokenClaims;
import com.example.funeventbackend.security.oauth.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 第三方登入的「決策樹」測試：驗證通過之後，要登入誰、要不要建帳號、要不要綁定。
 *
 * <p>⚠️ GoogleIdTokenVerifier 用 @MockitoBean 換掉 —— 真的那個會去打
 * Google 的 JWKS 端點，測試就變成需要網路而且會不穩。
 * 驗證邏輯本身已經有 GoogleIdTokenVerifierTest 的 6 個離線案例守著了，
 * 這裡測的是「驗證之後」的部分，兩者責任分開。
 */
@SpringBootTest
@ActiveProfiles("test")
class OAuthLoginServiceTest {
    private static final String GOOGLE_SUB = "google-sub-1234567890";
    private static final String EMAIL = "oauth-user@example.com";
    private static final String ID_TOKEN = "任何字串都行，verifier 已經被 mock 掉";

    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @Autowired
    private OAuthLoginService oAuthLoginService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserOAuthAccountRepository userOAuthAccountRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    private void mockClaims(String sub, String email, boolean emailVerified, String name) {
        when(googleIdTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleIdTokenClaims(sub, email, emailVerified, name));
    }

    @Test
    @DisplayName("全新的 Google 帳號登入：建出使用者與綁定，且該使用者沒有密碼")
    void createsUserOnFirstLogin() {
        mockClaims(GOOGLE_SUB, EMAIL, true, "測試使用者");

        AuthResponse response = oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN);

        assertEquals(EMAIL, response.email());
        assertEquals("測試使用者", response.name());
        assertEquals(RoleType.USER, response.role());
        assertEquals(1, userRepository.count());
        assertEquals(1, userOAuthAccountRepository.count());

        User created = userRepository.findByEmail(EMAIL).orElseThrow();
        // 第三方建立的帳號沒有密碼 —— 這是 passwordHash 改成可為 null 的理由
        assertFalse(created.hasPassword());
    }

    @Test
    @DisplayName("同一個 sub 再登入一次：不會重複建帳號，拿到同一個使用者")
    void reusesUserOnSecondLogin() {
        mockClaims(GOOGLE_SUB, EMAIL, true, "測試使用者");

        AuthResponse first = oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN);
        AuthResponse second = oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN);

        assertEquals(first.id(), second.id());
        assertEquals(1, userRepository.count());
        assertEquals(1, userOAuthAccountRepository.count());
    }

    @Test
    @DisplayName("email 已有密碼帳號且已被 Google 驗證：綁到既有帳號，不建新帳號")
    void linksToExistingUserWhenEmailVerified() {
        User existing = userRepository.save(User.builder()
                .email(EMAIL)
                .passwordHash(passwordEncoder.encode("password123"))
                .name("原本的使用者")
                .role(RoleType.USER)
                .build());

        mockClaims(GOOGLE_SUB, EMAIL, true, "Google 上的名字");

        AuthResponse response = oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN);

        assertEquals(existing.getId(), response.id());
        assertEquals(1, userRepository.count());
        assertEquals(1, userOAuthAccountRepository.count());
        // 綁定不會覆蓋既有的名字與密碼
        User reloaded = userRepository.findById(existing.getId()).orElseThrow();
        assertEquals("原本的使用者", reloaded.getName());
        assertTrue(reloaded.hasPassword());
    }

    @Test
    @DisplayName("⚠️ email 已有密碼帳號但未經 Google 驗證：拒絕自動綁定")
    void rejectsLinkWhenEmailNotVerified() {
        userRepository.save(User.builder()
                .email(EMAIL)
                .passwordHash(passwordEncoder.encode("password123"))
                .name("原本的使用者")
                .role(RoleType.USER)
                .build());

        // email_verified = false：Google 沒有驗證過這個 email，
        // 不能拿它當作「是同一個人」的證據
        mockClaims(GOOGLE_SUB, EMAIL, false, "冒充者");

        assertThrows(OAuthAccountLinkConflictException.class,
                () -> oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN));

        assertEquals(0, userOAuthAccountRepository.count());
    }

    @Test
    @DisplayName("對第三方建立的無密碼帳號用密碼登入：回 OAuthOnlyAccountException")
    void passwordLoginRejectedForOAuthOnlyAccount() {
        mockClaims(GOOGLE_SUB, EMAIL, true, "測試使用者");
        oAuthLoginService.loginWithGoogleIdToken(ID_TOKEN);

        // 這條分支在 user_oauth_accounts 做出來之前無法測試 —— 資料庫裡
        // 根本建不出 passwordHash 為 null 的使用者
        assertThrows(OAuthOnlyAccountException.class,
                () -> userService.login(new LoginRequest(EMAIL, "隨便猜一個密碼")));
    }
}
