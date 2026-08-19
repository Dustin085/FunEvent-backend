package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.auth.AuthResponse;
import com.example.funeventbackend.dto.auth.GoogleOAuthLoginRequest;
import com.example.funeventbackend.security.oauth.GoogleTokenExchanger;
import com.example.funeventbackend.model.OAuthProvider;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.security.JwtService;
import com.example.funeventbackend.security.oauth.GoogleIdTokenClaims;
import com.example.funeventbackend.security.oauth.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 第三方登入的匯流點：Google 的 ID Token 進來，本站的 JWT 出去。
 *
 * <p>對應 OAuth 流程的第 10～12 步。第 8 步（拿 code 換 token）不在這裡 ——
 * ⭐ 那是刻意的：未來 App 版會自己完成兌換，直接送 id_token 過來，
 * 那時只要加一支薄端點呼叫這裡，第 11、12 步的邏輯一行都不用重寫。
 *
 * <p>⚠️ 這個類別沒有 @Transactional。findOrCreate 的交易邊界必須是
 * 「每次呼叫一個」，這裡若包一層外層交易，重試會落在同一個已被標成
 * rollback-only 的交易裡。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthLoginService {
    private final GoogleTokenExchanger googleTokenExchanger;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final OAuthAccountLinker accountLinker;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    /**
     * 網頁版入口：完整走第 8～12 步。
     */
    public AuthResponse loginWithGoogleCode(GoogleOAuthLoginRequest request) {
        // 第 8、9 步：後端對後端，client_secret 只在這裡出現
        String idToken = googleTokenExchanger.exchangeCodeForIdToken(
                request.code(), request.codeVerifier(), request.redirectUri());
        return loginWithGoogleIdToken(idToken);
    }

    /**
     * 第 10～12 步。
     *
     * <p>⭐ 獨立成 public 方法是為了未來的 App 版 —— App 會用自己的 client_id
     * 完成兌換，直接把 id_token 送過來，那時只要加一支端點呼叫這裡。
     */
    public AuthResponse loginWithGoogleIdToken(String idToken) {
        // 第 10 步：驗簽章 / iss / aud / exp。這之前 idToken 裡的任何宣告都不可信
        GoogleIdTokenClaims claims = googleIdTokenVerifier.verify(idToken);

        // 第 11 步
        User user = findOrCreateWithRetry(claims);

        // 第 12 步：從這裡開始和帳密登入完全相同
        String accessToken = jwtService.generateToken(user);
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

    private User findOrCreateWithRetry(GoogleIdTokenClaims claims) {
        try {
            return accountLinker.findOrCreate(OAuthProvider.GOOGLE, claims);
        } catch (DataIntegrityViolationException e) {
            // 併發：同一個人的兩個請求都判斷「要建新帳號」，
            // user_oauth_accounts 的 UNIQUE(provider, provider_uid) 擋掉我們這筆。
            // 對方已經建好了，重查一次就會找到 —— 這是「靠資料庫做最後裁決」，
            // 和付款回呼用 merchant_trade_no 的 UNIQUE 是同一招。
            // 只重試一次：第二次還撞代表不是併發，是真的有別的約束出問題
            log.info("第三方帳號併發建立，重試一次 sub={}", claims.sub());
            return accountLinker.findOrCreate(OAuthProvider.GOOGLE, claims);
        }
    }
}
