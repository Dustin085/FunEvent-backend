package com.example.funeventbackend.security.oauth;

import com.example.funeventbackend.config.GoogleOAuthProperties;
import com.example.funeventbackend.exception.InvalidOAuthTokenException;
import com.example.funeventbackend.exception.OAuthProviderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 第 8、9 步（拿 code 換 token）的離線測試，用 MockRestServiceServer 假裝 Google。
 *
 * <p>重點在兩個錯誤路徑要分得開：
 * 「Google 說這張 code 不行」是使用者的問題（401），
 * 「Google 掛了 / 連不上」不是（502）。合成一種的話，Google 出事時
 * 使用者會看到「登入憑證無效」然後一直重試。
 *
 * <p>⚠️ 這個測試驗不到「表單欄位名稱是否符合 Google 的規格」——
 * 測試和實作是同一個人寫的，拼錯了兩邊會一起錯。
 * 那一段只能靠實際跑一次真的登入來驗證。
 */
class GoogleTokenExchangerTest {
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String REDIRECT_URI =
            "http://localhost:3000/api/auth/oauth/google/callback";

    private MockRestServiceServer server;
    private GoogleTokenExchanger exchanger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        // ⚠️ 順序：bindTo 會換掉 builder 的 request factory，
        // 必須在 build() 之前呼叫，否則建出來的 RestClient 會真的打到 Google
        server = MockRestServiceServer.bindTo(builder).build();
        exchanger = new GoogleTokenExchanger(builder.build(), new GoogleOAuthProperties(
                CLIENT_ID, CLIENT_SECRET, List.of(CLIENT_ID)));
    }

    @Test
    @DisplayName("兌換成功：取出 id_token，且表單帶了 PKCE verifier 與 client_secret")
    void exchangesCodeForIdToken() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("grant_type=authorization_code")))
                .andExpect(content().string(containsString("code=the-auth-code")))
                .andExpect(content().string(containsString("code_verifier=the-verifier")))
                .andExpect(content().string(containsString("client_secret=" + CLIENT_SECRET)))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ya29.a0Af...",
                          "expires_in": 3599,
                          "scope": "openid email profile",
                          "token_type": "Bearer",
                          "id_token": "the.id.token"
                        }
                        """, MediaType.APPLICATION_JSON));

        String idToken = exchanger.exchangeCodeForIdToken(
                "the-auth-code", "the-verifier", REDIRECT_URI);

        assertEquals("the.id.token", idToken);
        server.verify();
    }

    @Test
    @DisplayName("Google 回 400 invalid_grant（code 過期／用過／verifier 不符）→ 401")
    void mapsClientErrorToInvalidToken() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":"invalid_grant","error_description":"Bad Request"}
                                """));

        assertThrows(InvalidOAuthTokenException.class, () -> exchanger.exchangeCodeForIdToken(
                "used-code", "the-verifier", REDIRECT_URI));
    }

    @Test
    @DisplayName("⚠️ Google 回 500 → 502 而不是 401（不是使用者的問題）")
    void mapsServerErrorToProviderUnavailable() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(OAuthProviderUnavailableException.class,
                () -> exchanger.exchangeCodeForIdToken(
                        "the-auth-code", "the-verifier", REDIRECT_URI));
    }

    @Test
    @DisplayName("回 200 但沒有 id_token（scope 漏了 openid 就會這樣）→ 502")
    void rejectsResponseWithoutIdToken() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("""
                        {"access_token":"ya29.a0Af...","token_type":"Bearer"}
                        """, MediaType.APPLICATION_JSON));

        assertThrows(OAuthProviderUnavailableException.class,
                () -> exchanger.exchangeCodeForIdToken(
                        "the-auth-code", "the-verifier", REDIRECT_URI));
    }
}
