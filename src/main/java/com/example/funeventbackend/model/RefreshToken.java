package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", indexes = {
        // index 名稱是 schema 全域唯一的，故應該加上表名，以免未來撞名
        @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    /**
     * 這張票是「什麼時候」被換掉的，寬限期從這一刻起算。
     *
     * <p>⚠️ 可為 null：登出時整條 family 會被標記 used 但沒有 usedAt，
     * 這個欄位存在之前的舊資料也是 null。判斷寬限期時 null 一律視為窗口外
     * —— 安全的預設是拒絕，不是放行。
     */
    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * ⚠️ 和 used 是兩件不同的事，不能共用一個欄位：
     * <ul>
     *   <li>{@code used} —— 這張票已經換過新的了（正常流程）</li>
     *   <li>{@code revoked} —— 這條 family 被判定竊用而整條作廢（安全事件）</li>
     * </ul>
     * 擠在同一個欄位裡的話，「family 已撤銷、但原始 token 還在寬限期內」
     * 會通過寬限期檢查繼續發票，撤銷等於白做。詳見 RefreshTokenService.rotate。
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
