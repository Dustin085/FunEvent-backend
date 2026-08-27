package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.LoginRequest;
import com.example.funeventbackend.dto.user.ChangePasswordRequest;
import com.example.funeventbackend.dto.user.UpdateProfileRequest;
import com.example.funeventbackend.exception.InvalidCredentialsException;
import com.example.funeventbackend.exception.InvalidPasswordException;
import com.example.funeventbackend.exception.OAuthOnlyAccountException;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 會員中心的帳號功能：改名字、改密碼。
 *
 * <p>⭐ 改密碼那組是重點 —— 它同時要做三件事，少任何一件都不會報錯：
 * 換掉密碼、撤銷其他裝置的 refresh token、給自己一組新的票。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceTest {

    private static final String OLD_PASSWORD = "oldPassword123";
    private static final String NEW_PASSWORD = "newPassword456";

    @Autowired
    private UserService userService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private User user;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        user = userRepository.save(User.builder()
                .email("account@example.com")
                .passwordHash(passwordEncoder.encode(OLD_PASSWORD))
                .name("原本的名字")
                .role(RoleType.USER)
                .build());
    }

    @Test
    @DisplayName("⭐ 改名字要真的寫進資料庫")
    void updateProfileNamePersists() {
        var response = userService.updateProfile(user, new UpdateProfileRequest("新的名字"));

        assertEquals("新的名字", response.name());
        // ⚠️ 這條斷言是重點，不是多餘的：principal 帶來的 User 是 JWT filter
        // 在請求早期載入的，到 service 層已經 detached。
        // 直接對它 setName() 回傳值會是對的，但**資料庫不會變**，而且不會報錯。
        // 只有重新讀一次才抓得到那個錯誤
        assertEquals("新的名字", userRepository.findById(user.getId()).orElseThrow().getName());
    }

    @Test
    @DisplayName("改密碼：舊密碼失效、新密碼可以登入")
    void changePasswordSwapsThePassword() {
        userService.changePassword(user, changeRequest(OLD_PASSWORD, NEW_PASSWORD));

        assertThrows(InvalidCredentialsException.class,
                () -> userService.login(new LoginRequest("account@example.com", OLD_PASSWORD)));
        var loggedIn = userService.login(new LoginRequest("account@example.com", NEW_PASSWORD));
        assertEquals(user.getId(), loggedIn.id());
    }

    @Test
    @DisplayName("目前密碼打錯 → InvalidPasswordException（400，不是 401）")
    void wrongCurrentPasswordIsRejected() {
        assertThrows(InvalidPasswordException.class,
                () -> userService.changePassword(user, changeRequest("完全不對的密碼", NEW_PASSWORD)));

        // 沒改成功，舊密碼還能用
        assertTrue(passwordEncoder.matches(OLD_PASSWORD,
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash()));
    }

    @Test
    @DisplayName("⭐ 改密碼要撤銷其他裝置的 refresh token")
    void changePasswordRevokesExistingTokens() {
        // 兩台既有裝置
        String phone = refreshTokenService.issueNewFamily(user);
        String laptop = refreshTokenService.issueNewFamily(user);

        userService.changePassword(user, changeRequest(OLD_PASSWORD, NEW_PASSWORD));

        // ⚠️ 沒有這段的話「改密碼」只是換了一個字串 ——
        // 盜用者手上的舊 token 照樣能一直換新的，改了等於沒改
        assertInstanceOf(RotationOutcome.Rejected.class,
                refreshTokenService.rotate(phone));
        assertInstanceOf(RotationOutcome.Rejected.class,
                refreshTokenService.rotate(laptop));
    }

    @Test
    @DisplayName("⭐ 但自己不會被登出 —— 回傳的新票是可用的")
    void changePasswordKeepsCurrentSessionAlive() {
        refreshTokenService.issueNewFamily(user);

        AuthResponse response = userService.changePassword(
                user, changeRequest(OLD_PASSWORD, NEW_PASSWORD));

        // 撤銷是先做的，新票是之後才發的 —— 順序反過來的話新票會被自己撤銷掉
        assertInstanceOf(RotationOutcome.Rotated.class,
                refreshTokenService.rotate(response.refreshToken()));
        assertFalse(response.accessToken().isBlank());
    }

    @Test
    @DisplayName("第三方登入的帳號沒有密碼，「修改」不成立")
    void oauthOnlyAccountCannotChangePassword() {
        User oauthUser = userRepository.save(User.builder()
                .email("google@example.com")
                .passwordHash(null)   // ⚠️ Google 建立的帳號就是這樣
                .name("Google 使用者")
                .role(RoleType.USER)
                .build());

        assertThrows(OAuthOnlyAccountException.class,
                () -> userService.changePassword(oauthUser, changeRequest("隨便", NEW_PASSWORD)));
    }

    @Test
    @DisplayName("hasPassword 要正確反映帳號能不能用密碼登入")
    void hasPasswordReflectsAccountType() {
        User oauthUser = userRepository.save(User.builder()
                .email("google2@example.com").passwordHash(null)
                .name("Google 使用者").role(RoleType.USER).build());

        // 前端靠這個欄位決定顯示「修改密碼」表單還是說明文字
        assertTrue(userService.updateProfile(user, new UpdateProfileRequest("原本的名字"))
                .hasPassword());
        assertFalse(userService.updateProfile(oauthUser, new UpdateProfileRequest("Google 使用者"))
                .hasPassword());
    }

    private ChangePasswordRequest changeRequest(String current, String next) {
        return new ChangePasswordRequest(current, next);
    }
}
