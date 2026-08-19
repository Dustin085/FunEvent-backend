package com.example.funeventbackend.exception;

/**
 * 第三方帳號的 email 已經被本站帳號使用，但該 email 未經第三方驗證，
 * 不能自動綁定。
 *
 * <p>⚠️ 為什麼不自動綁：provider 若沒驗證過那個 email，
 * 「宣稱擁有 a@example.com」這件事就沒有任何保證 ——
 * 自動綁等於任何人只要在 provider 那邊填上你的 email 就能接管你的帳號。
 */
public class OAuthAccountLinkConflictException extends RuntimeException {
    public OAuthAccountLinkConflictException(String message) {
        super(message);
    }
}
