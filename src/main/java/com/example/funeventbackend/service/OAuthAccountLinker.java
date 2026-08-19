package com.example.funeventbackend.service;

import com.example.funeventbackend.exception.OAuthAccountLinkConflictException;
import com.example.funeventbackend.model.OAuthProvider;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.model.UserOAuthAccount;
import com.example.funeventbackend.repository.UserOAuthAccountRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.oauth.GoogleIdTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 把「第三方身分」對應到「本站帳號」——OAuth 流程的第 11 步。
 *
 * <p>⚠️ 這是獨立的 bean 而不是 OAuthLoginService 的一個私有方法，
 * 因為併發衝突時要在**新的交易**裡重試。同一個類別內的自我呼叫不會經過
 * AOP 代理，@Transactional 會完全失效 —— 那樣重試只會拿到
 * UnexpectedRollbackException（前一個交易已被標成 rollback-only）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAccountLinker {
    private final UserRepository userRepository;
    private final UserOAuthAccountRepository userOAuthAccountRepository;

    @Transactional
    public User findOrCreate(OAuthProvider provider, GoogleIdTokenClaims claims) {
        // ① 這個第三方帳號已經綁過了 —— 最常見的路徑（第二次以後的登入）
        Optional<UserOAuthAccount> linked =
                userOAuthAccountRepository.findByProviderAndProviderUid(provider, claims.sub());
        if (linked.isPresent()) {
            return linked.get().getUser();
        }

        // ② 沒綁過，但這個 email 已經有本站帳號 —— 使用者先用密碼註冊，之後改用 Google
        Optional<User> existingByEmail = userRepository.findByEmail(claims.email());
        if (existingByEmail.isPresent()) {
            if (!claims.emailVerified()) {
                // ⚠️ provider 沒驗證過這個 email，不能拿它當作「同一個人」的證據
                log.warn("拒絕自動綁定：email 未經第三方驗證 provider={} sub={}",
                        provider, claims.sub());
                throw new OAuthAccountLinkConflictException(
                        "此 email 已註冊，請先用密碼登入後再綁定第三方帳號");
            }
            log.info("將第三方帳號綁定到既有使用者 userId={} provider={}",
                    existingByEmail.get().getId(), provider);
            return link(existingByEmail.get(), provider, claims.sub());
        }

        // ③ 全新的使用者
        User newUser = userRepository.save(User.builder()
                .email(claims.email())
                // ⚠️ 沒有密碼。User.hasPassword() 會回 false，
                // 密碼登入與忘記密碼都會據此擋下來
                .passwordHash(null)
                // Google 在 scope 含 profile 時會給 name；沒給就先用 email 頂著，
                // 否則畫面上會出現「你好，」
                .name(claims.name() != null ? claims.name() : claims.email())
                .role(RoleType.USER)
                .build());
        log.info("以第三方登入建立新使用者 userId={} provider={}", newUser.getId(), provider);
        return link(newUser, provider, claims.sub());
    }

    private User link(User user, OAuthProvider provider, String providerUid) {
        userOAuthAccountRepository.save(UserOAuthAccount.builder()
                .user(user)
                .provider(provider)
                .providerUid(providerUid)
                .build());
        return user;
    }
}
