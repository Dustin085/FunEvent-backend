package com.example.funeventbackend.exception;

/**
 * 帳號是透過第三方登入建立的，沒有密碼，卻嘗試用密碼登入。
 *
 * <p>⚠️ 這個例外的訊息會告訴對方「這個 email 存在且是第三方帳號」——
 * 它和 {@link InvalidCredentialsException} 刻意不洩漏註冊狀態的立場不同。
 * 這是取捨後的決定：使用者若只看到「帳號或密碼錯誤」會永遠登不進來，
 * 那是確定發生的傷害，比列舉風險嚴重。
 *
 * <p>但忘記密碼那支端點維持沉默（見 PasswordResetService.requestReset），
 * 因為那裡不告知也不會讓任何人卡住。
 */
public class OAuthOnlyAccountException extends RuntimeException {
    public OAuthOnlyAccountException(String message) {
        super(message);
    }
}
