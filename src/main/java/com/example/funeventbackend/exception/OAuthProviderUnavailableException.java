package com.example.funeventbackend.exception;

/**
 * 連不上第三方，或第三方回了 5xx / 無法理解的回應。
 *
 * <p>⚠️ 和 InvalidOAuthTokenException 分開，是因為責任歸屬不同：
 * 那個是「使用者的憑證有問題」（401），這個是「我們或 Google 出問題」（502）。
 * 全部歸成 401 的話，Google 掛掉時會顯示「登入憑證無效」——
 * 使用者會一直重試，而問題根本不在他身上。
 */
public class OAuthProviderUnavailableException extends RuntimeException {
    public OAuthProviderUnavailableException(String message) {
        super(message);
    }
}
