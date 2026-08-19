package com.example.funeventbackend.security.oauth;

/**
 * 從 Google ID Token 取出的、我們用得到的欄位。
 *
 * @param sub           Google 的使用者識別碼。⚠️ 綁定帳號只能用它，不能用 email
 * @param email         使用者的 email
 * @param emailVerified Google 是否已驗證這個 email。
 *                      ⚠️ 決定「能不能自動綁到既有的密碼帳號」的關鍵 ——
 *                      未驗證就自動綁，等於任何人宣稱擁有某 email 就能接管該帳號
 * @param name          顯示名稱，可能為 null
 */
public record GoogleIdTokenClaims(
        String sub,
        String email,
        boolean emailVerified,
        String name
) {
}
