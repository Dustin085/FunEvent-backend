package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.LoginRequest;
import com.example.funeventbackend.dto.auth.RegisterRequest;
import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.dto.user.ChangePasswordRequest;
import com.example.funeventbackend.dto.user.UpdateProfileRequest;
import com.example.funeventbackend.exception.EmailAlreadyExistsException;
import com.example.funeventbackend.exception.InvalidCredentialsException;
import com.example.funeventbackend.exception.InvalidPasswordException;
import com.example.funeventbackend.exception.OAuthOnlyAccountException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public UserResponse register(RegisterRequest dto) {
        // 驗證 Email 是否已經使用過
        if (userRepository.existsByEmail(dto.email())) {
            throw new EmailAlreadyExistsException(dto.email());
        }
        // hash 密碼
        String hashedPassword = passwordEncoder.encode(dto.password());
        // 建立新 User
        User newUser = User.builder()
                .email(dto.email())
                .passwordHash(hashedPassword)
                .name(dto.name())
                .role(RoleType.USER)
                .build();
        // 存入資料庫
        User savedUser = userRepository.save(newUser);
        return convertToResponse(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest dto) {
        // 嘗試使用 email 撈 User，驗證 User 是否存在
        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(() -> new InvalidCredentialsException("帳號或密碼錯誤"));
        // ⚠️ 第三方登入建立的帳號沒有密碼，要在 matches() 之前擋掉。
        // 不擋的話 BCrypt 對 null 會回 false（不會拋 NPE），使用者只看到
        // 「帳號或密碼錯誤」—— 他從沒設過密碼，會永遠卡在這裡重試，
        // 而且每次都印一行 warn。
        if (!user.hasPassword()) {
            throw new OAuthOnlyAccountException("此帳號是使用第三方登入建立的，請改用原本的登入方式");
        }
        // 驗證密碼
        if (!passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("帳號或密碼錯誤");
        }
        String accessToken = jwtService.generateToken(user);
        // 建立新 refresh token family
        String refreshToken = refreshTokenService.issueNewFamily(user);
        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                accessToken,
                refreshToken
        );
    }

    /**
     * ⚠️ 回傳 {@code Optional} 而不是丟例外：empty 代表 refresh token 無效。
     *
     * <p>這個方法是 {@code @Transactional}，和 {@code rotate} 共用同一個實體交易 ——
     * 在這裡丟例外會回滾掉 {@code rotate} 剛做的竊用撤銷。
     * 例外必須由 Controller 丟（那一層沒有交易）。
     */
    @Transactional
    public Optional<AuthResponse> refresh(String refreshToken) {
        // 密封介面 → switch 有窮盡性檢查，之後多一種結果編譯器會逼你處理
        return switch (refreshTokenService.rotate(refreshToken)) {
            case RefreshTokenService.RotationOutcome.Rejected ignored -> Optional.empty();
            case RefreshTokenService.RotationOutcome.Rotated rotated -> {
                User user = rotated.user();
                yield Optional.of(new AuthResponse(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getRole(),
                        jwtService.generateToken(user),
                        rotated.rawToken()
                ));
            }
        };
    }

    public UserResponse getCurrentUser(CustomUserDetails principal) {
        return convertToResponse(principal.getUser());
    }

    @Transactional
    public UserResponse updateProfile(User user, UpdateProfileRequest dto) {
        // ⚠️ 一定要重新載入成 managed entity：principal 帶來的 User 是
        // JWT filter 在請求早期載入的，到這裡已經 detached ——
        // 直接對它 setName() 不會寫進資料庫，而且不會有任何錯誤訊息
        User managed = getUserEntity(user.getId());
        managed.setName(dto.name());
        // managed entity，髒檢查會在提交時自動 UPDATE，不需要 save()
        return convertToResponse(managed);
    }

    /**
     * 改密碼，並撤銷這個使用者的所有 refresh token。
     *
     * <p>⭐ 撤銷是重點：改密碼通常意味著「我懷疑帳號被別人拿到了」，
     * 舊裝置必須被登出，否則改了等於沒改。
     *
     * <p>⚠️ 但 access token 是無狀態 JWT，撤銷 refresh token <b>不會</b>讓它立刻失效 ——
     * 其他裝置最多還能再用一個 AT 效期（15 分鐘）。要立即失效需要 token 黑名單，
     * 那等於把無狀態改回有狀態。這是刻意接受的取捨。
     *
     * <p>回傳新的一組 token：不重發的話，使用者改完密碼會把自己也登出。
     */
    @Transactional
    public AuthResponse changePassword(User user, ChangePasswordRequest dto) {
        User managed = getUserEntity(user.getId());

        // ⚠️ 第三方登入的帳號沒有密碼，「修改」不成立 ——
        // 那是「設定密碼」，是另一支端點與另一套流程
        if (!managed.hasPassword()) {
            throw new OAuthOnlyAccountException("此帳號是使用第三方登入建立的，尚未設定密碼");
        }
        if (!passwordEncoder.matches(dto.currentPassword(), managed.getPasswordHash())) {
            throw new InvalidPasswordException("目前的密碼不正確");
        }

        managed.setPasswordHash(passwordEncoder.encode(dto.newPassword()));

        // ⚠️ 順序不能反：先撤銷既有的，再發新的 ——
        // 反過來的話，新發的那一張會被自己撤銷掉
        refreshTokenService.revokeAllForUser(managed.getId());

        String accessToken = jwtService.generateToken(managed);
        String refreshToken = refreshTokenService.issueNewFamily(managed);
        return new AuthResponse(
                managed.getId(),
                managed.getEmail(),
                managed.getName(),
                managed.getRole(),
                accessToken,
                refreshToken
        );
    }

    /**
     * 取得特定 User Entity 僅供後端內部使用，回傳給前端應使用 DTO
     *
     * @param id 要尋找的使用者 id
     * @return 此 id 的 User Entity
     */
    @Transactional(readOnly = true)
    public User getUserEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("找不到使用者"));
    }

    /**
     * 將 User Entity 轉換成 UserResponse
     *
     * @param user 要被轉換的 User Entity
     * @return 符合傳入的 User 狀態的 UserResponse
     */
    private UserResponse convertToResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.hasPassword()
        );
    }
}
