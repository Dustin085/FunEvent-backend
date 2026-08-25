package com.example.funeventbackend.exception;

/**
 * 使用者在「已登入」的狀態下提供了錯誤的密碼（例如改密碼時的「目前密碼」）。
 *
 * <p>⚠️ 刻意不沿用 {@link InvalidCredentialsException} —— 那個對應 401，
 * 語意是「你還沒通過驗證」。改密碼發生在一個已通過驗證的端點上，
 * session 是好的，只是請求內容裡的某個值不對，所以是 400。
 * 混用的話，哪天前端加上「遇 401 就導向登入頁」，
 * 使用者只是把舊密碼打錯就會被踢出去。
 */
public class InvalidPasswordException extends RuntimeException {
    public InvalidPasswordException(String message) {
        super(message);
    }
}
