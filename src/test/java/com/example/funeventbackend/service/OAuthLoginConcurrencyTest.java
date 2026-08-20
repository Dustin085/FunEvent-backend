package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 同一個人第一次用 Google 登入，多個請求同時進來會怎樣。
 *
 * <p>每條執行緒都會查到「還沒有這個帳號」，於是都去建 —— 只有一個能成功，
 * 其餘會撞上 user_oauth_accounts 的 UNIQUE(provider, provider_uid)。
 *
 * <p>⚠️ 沒有 OAuthLoginService 的重試機制，這裡會有 9 條執行緒拿到
 * DataIntegrityViolationException。而重試之所以必須放在
 * OAuthAccountLinker 之外的另一個 bean，是因為衝突已經把當下那個交易
 * 標成 rollback-only，同一個交易裡重查只會換成 UnexpectedRollbackException。
 */
@SpringBootTest
@ActiveProfiles("test")
class OAuthLoginConcurrencyTest {
    private static final int THREAD_COUNT = 10;
    private static final String GOOGLE_SUB = "google-sub-concurrent";
    private static final String EMAIL = "concurrent@example.com";

    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @Autowired
    private OAuthLoginService oAuthLoginService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserOAuthAccountRepository userOAuthAccountRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        when(googleIdTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleIdTokenClaims(GOOGLE_SUB, EMAIL, true, "併發測試"));
    }

    @Test
    @DisplayName("十個請求同時第一次登入：只會建出一個使用者，且十個都拿到同一個")
    void concurrentFirstLoginCreatesExactlyOneUser() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        // 先讓全部執行緒就位，再一起放行 —— 不然它們會依序執行，撞不到一起
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREAD_COUNT);
        Queue<Long> userIds = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        AuthResponse response = oAuthLoginService.loginWithGoogleIdToken("fake-id-token");
                        userIds.add(response.id());
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(finished.await(30, TimeUnit.SECONDS), "執行緒未在時限內結束");
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(),
                "不應有請求失敗，但有 " + failures.size() + " 個：" + failures);
        assertEquals(THREAD_COUNT, userIds.size());

        // 核心斷言：不管幾條執行緒，最後只能有一個使用者與一筆綁定
        assertEquals(1, userRepository.count(), "重複建立了使用者");
        assertEquals(1, userOAuthAccountRepository.count(), "重複建立了綁定");

        Set<Long> distinctIds = userIds.stream().collect(Collectors.toSet());
        assertEquals(1, distinctIds.size(), "不同請求拿到了不同的使用者：" + distinctIds);
    }
}
