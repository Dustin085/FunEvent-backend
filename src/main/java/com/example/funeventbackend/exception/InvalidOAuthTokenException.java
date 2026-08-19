package com.example.funeventbackend.exception;

/**
 * 第三方登入的憑證無效（簽章、aud、iss、過期、缺欄位）。
 *
 * <p>⚠️ 對外一律同一句話，細節只進 log ——
 * 講明「你只差 aud 沒對」等於在幫攻擊者除錯。
 */
public class InvalidOAuthTokenException extends RuntimeException {
    public InvalidOAuthTokenException(String message) {
        super(message);
    }
}
