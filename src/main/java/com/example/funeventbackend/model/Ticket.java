package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 一張可入場的票。
 *
 * <p>⭐ 一張票一列 —— 買三張就是三筆，這樣三個人才能分開入場。
 * {@code OrderItem} 的 quantity 在付款成功時被展開成 N 筆 Ticket。
 *
 * <p>⚠️ <b>刻意沒有 token 欄位</b>。QR 的內容是
 * {@code {ticketId}.{HMAC(secret, ticketId)}}，由 {@code TicketTokenSigner}
 * 現算現驗。理由是「我的票券」頁每次打開都要重畫 QR ——
 * 若像 refresh token 那樣只存雜湊，伺服器就再也算不回原文了。
 * 而存明文則是資料庫外洩後所有票都能被偽造。簽章兩個問題都沒有。
 */
@Entity
@Table(name = "tickets", indexes = {
        // 「這張訂單的票」是最主要的查詢。index 名稱是 schema 全域唯一的，故帶上表名
        @Index(name = "idx_tickets_order_item", columnList = "order_item_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.VALID;

    @Column(name = "used_at")
    private Instant usedAt;

    /** 核銷的工作人員。⚠️ 可為 null —— 還沒被使用的票沒有這個值 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_in_by")
    private User checkedInBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
