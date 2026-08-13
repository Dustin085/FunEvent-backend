package com.example.funeventbackend.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "payments",
        check = @CheckConstraint(
                name = "ck_payments_amount_non_negative",
                constraint = "amount >= 0"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 一張訂單可以有多筆付款嘗試（第一次刷卡失敗、換張卡再刷）
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 我方產生的交易編號，送給金流商，回呼時靠它找回這筆記錄。
    // UNIQUE 是整個冪等性的地基：同一個編號不可能對應到兩筆付款。
    @Column(name = "merchant_trade_no", nullable = false, unique = true, length = 20)
    private String merchantTradeNo;

    // 金流商的交易編號，回呼之後才會有，用於日後對帳與退款
    @Column(name = "gateway_trade_no")
    private String gatewayTradeNo;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private PaymentStatusType status = PaymentStatusType.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    // 原始回呼內容存證。金額有爭議時這是你唯一的證據
    @Column(name = "raw_callback", columnDefinition = "TEXT")
    private String rawCallback;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
