package com.example.funeventbackend.repository;

import com.example.funeventbackend.model.OAuthProvider;
import com.example.funeventbackend.model.UserOAuthAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserOAuthAccountRepository extends JpaRepository<UserOAuthAccount, Long> {
    /**
     * 第三方登入的第一個動作：這個 Google 帳號綁到哪個本地帳號了？
     *
     * <p>⚠️ 帶 @EntityGraph 把 user 一起撈出來 —— 找到之後緊接著就要用 user
     * 去發 JWT，不預先抓的話那個 LAZY 代理會再送一句查詢。
     * 這裡是 to-one 又沒有分頁，用 @EntityGraph 是安全的
     *（對集合欄位分頁才會退化成記憶體分頁）。
     */
    @EntityGraph(attributePaths = "user")
    Optional<UserOAuthAccount> findByProviderAndProviderUid(
            OAuthProvider provider, String providerUid);
}
