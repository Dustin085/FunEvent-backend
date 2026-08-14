package com.example.funeventbackend.service;

import com.example.funeventbackend.model.PasswordResetToken;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.PasswordResetTokenRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.TokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密碼重設的變更是否真的落到資料庫。
 * <p>
 * 這兩個方法裡的 {@code save()} 已被移除，改由髒檢查處理 ——
 * 這個測試就是那個決定的保護網。
 * <p>
 * ⚠️ 刻意不加 @Transactional：測試自己的交易若不提交，
 * 讀回來的會是同一個持久化上下文裡的物件，就算什麼都沒寫進 DB 也會是綠的。
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetPersistenceTest {
    private static final String EMAIL = "reset@example.com";
    private static final String OLD_PASSWORD = "oldpassword123";
    private static final String NEW_PASSWORD = "newpassword456";

    /**
     * EmailService 是我們控制不了的外部依賴（真的會連 SMTP），
     * 這是這個專案第一個值得用 mock 的地方。
     */
    @MockitoBean
    private EmailService emailService;

    @Autowired
    private PasswordResetService passwordResetService;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenGenerator tokenGenerator;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private TicketTypeRepository ticketTypeRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private User user;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(User.builder()
                .email(EMAIL)
                .passwordHash(passwordEncoder.encode(OLD_PASSWORD))
                .name("測試使用者")
                .role(RoleType.USER)
                .build());
    }

    @Test
    @DisplayName("重設密碼：新密碼與 used 標記都要寫進資料庫")
    void resetPasswordPersistsBothChanges() {
        String rawToken = tokenGenerator.generateRawToken();
        PasswordResetToken token = passwordResetTokenRepository.save(PasswordResetToken.builder()
                .tokenHash(tokenGenerator.hashToken(rawToken))
                .user(user)
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build());

        passwordResetService.resetPassword(rawToken, NEW_PASSWORD);

        User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, reloadedUser.getPasswordHash()),
                "新密碼必須寫進資料庫");
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, reloadedUser.getPasswordHash()),
                "舊密碼必須失效");

        assertTrue(passwordResetTokenRepository.findById(token.getId()).orElseThrow().isUsed(),
                "token 必須被標記為已使用，否則可以重複重設密碼");
    }

    @Test
    @DisplayName("再次申請重設：舊 token 要被作廢，並產生一筆新的")
    void requestResetInvalidatesPreviousToken() {
        PasswordResetToken oldToken = passwordResetTokenRepository.save(PasswordResetToken.builder()
                .tokenHash(tokenGenerator.hashToken(tokenGenerator.generateRawToken()))
                .user(user)
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build());

        passwordResetService.requestReset(EMAIL);

        assertTrue(passwordResetTokenRepository.findById(oldToken.getId()).orElseThrow().isUsed(),
                "舊 token 必須被作廢，否則兩條重設連結會同時有效");
        assertEquals(2, passwordResetTokenRepository.count(), "應該多出一筆新的 token");
    }
}
