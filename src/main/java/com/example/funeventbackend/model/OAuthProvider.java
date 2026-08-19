package com.example.funeventbackend.model;

/**
 * 第三方登入的提供者。
 *
 * <p>目前只有 Google。做成 enum 而不是自由字串的理由和 {@link City} 一樣 ——
 * 它是查詢的鍵（每次登入都用 provider + uid 去找帳號），
 * 字串會讓「google」「GOOGLE」「Google」變成三種不同的資料。
 *
 * <p>之後加 Line、Facebook 就是多一個列舉值，
 * 加上一支對應的端點與一個 ID Token 驗證器。
 */
public enum OAuthProvider {
    GOOGLE
}
