package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 活動評論。
 *
 * <p>⚠️ 評價是對「活動」的意見，不是對訂單的 —— 所以唯一約束是
 * (event, user) 而不是 (order, user)。同一個人可能買兩次票，但只該有一則評價。
 */
@Entity
@Table(name = "comments", uniqueConstraints = {
        // ⚠️ 一人一活動一則。同時是併發防線：兩個分頁同時送出，資料庫擋掉一個
        @UniqueConstraint(name = "uk_comments_event_user",
                columnNames = {"event_id", "user_id"})
}, indexes = {
        // 列表查詢固定是「用 event_id 篩選 + 依 created_at 排序」
        @Index(name = "idx_comments_event_created", columnList = "event_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 1～5 分。
     * ⚠️ CHECK 放在資料庫層 —— Bean Validation 只擋得住 API，
     * 直接寫資料庫的路徑（seeder、修資料的 SQL）擋不到
     */
    @Column(nullable = false, columnDefinition = "int check (rating between 1 and 5)")
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
