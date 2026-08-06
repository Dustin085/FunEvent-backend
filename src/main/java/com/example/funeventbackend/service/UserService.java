package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.LoginRequest;
import com.example.funeventbackend.dto.auth.RegisterRequest;
import com.example.funeventbackend.dto.auth.UserResponse;
import com.example.funeventbackend.exception.EmailAlreadyExistsException;
import com.example.funeventbackend.exception.InvalidCredentialsException;
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

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest dto) {
        // 嘗試使用 email 撈 User，驗證 User 是否存在
        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(() -> new InvalidCredentialsException("帳號或密碼錯誤"));
        // 驗證密碼
        if (!passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("帳號或密碼錯誤");
        }
        String token = jwtService.generateToken(user);
        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                token
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
