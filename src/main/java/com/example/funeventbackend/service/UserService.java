package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.LoginRequest;
import com.example.funeventbackend.dto.auth.RegisterRequest;
import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.exception.EmailAlreadyExistsException;
import com.example.funeventbackend.exception.InvalidCredentialsException;
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

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotationResult = refreshTokenService.rotate(refreshToken);
        User user = rotationResult.user();
        String accessToken = jwtService.generateToken(user);

        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                accessToken,
                rotationResult.rawToken()
        );
    }

    public UserResponse getCurrentUser(CustomUserDetails principal) {
        return convertToResponse(principal.getUser());
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
                user.getRole()
        );
    }
}
