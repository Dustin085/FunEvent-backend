package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", indexes = {
        // 逾時掃描的查詢條件就是這兩欄。index 名稱是 schema 全域唯一的，故帶上表名
        @Index(name = "idx_orders_status_expires_at", columnList = "status, expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id",  nullable = false)
    private User user;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private OrderStatusType status = OrderStatusType.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * 付款期限。逾時未付款會被排程取消並回補庫存。
     *
     * <p>⚠️ 存下來而不是用 createdAt + 設定值算：
     * 「你有 15 分鐘完成付款」是建單當下對使用者的承諾，
     * 改設定值時既有訂單的期限不應該跟著跳動。
     * 前端要顯示倒數也直接讀這個欄位，不必再複製一份 timeout 常數。
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();

    /**
     * 加入訂單明細。
     * <p>
     * 一定要走這個方法，不要直接 {@code getOrderItems().add(...)}：
     * mappedBy 那一端是被動方，Hibernate 只看 {@code OrderItem.order} 決定要寫什麼進 DB。
     * 只更新集合的話資料不會落地；只 setOrder 的話記憶體裡的集合會是空的。
     */
    public void addOrderItem(OrderItem orderItem) {
        orderItems.add(orderItem);
        orderItem.setOrder(this);
    }

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
