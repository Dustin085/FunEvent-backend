package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 本地帳號與第三方帳號的綁定關係。
 *
 * <p>不在 users 表上加 google_id 欄位，是因為之後接 Line、Facebook 就要再加一欄，
 * 而且「一個人綁了哪些登入方式」本來就是一對多的關係。
 *
 * <p>⚠️ 識別第三方帳號一律用 providerUid（Google 的 sub），不能用 email。
 * email 是會變的，sub 才是 Google 保證穩定且唯一的識別碼。
 */
@Entity
@Table(
        name = "user_oauth_accounts",
        uniqueConstraints = {
                // 一個第三方帳號只能綁一個本地帳號。
                // ⚠️ 這條同時是第一次登入時的併發防線：兩個分頁同時建帳號，
                // 資料庫會擋掉其中一個，我們捕捉例外後重查即可
                @UniqueConstraint(
                        name = "uk_user_oauth_accounts_provider_uid",
                        columnNames = {"provider", "provider_uid"}),
                // 反過來，同一個本地帳號的同一個 provider 也只能綁一組
                @UniqueConstraint(
                        name = "uk_user_oauth_accounts_user_provider",
                        columnNames = {"user_id", "provider"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserOAuthAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    /**
     * 第三方的使用者識別碼。Google 是 ID Token 裡的 sub。
     * 長度取 255：OIDC 規範對 sub 的上限就是 255 個 ASCII 字元。
     */
    @Column(name = "provider_uid", nullable = false, length = 255)
    private String providerUid;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
