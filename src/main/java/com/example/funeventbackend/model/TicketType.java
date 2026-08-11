package com.example.funeventbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "ticket_types",
        check = {
                @CheckConstraint(
                        name = "ck_ticket_types_stock_non_negative",
                        constraint = "stock >= 0"
                ),
                @CheckConstraint(
                        name = "ck_ticket_types_stock_within_capacity",
                        constraint = "stock <= capacity"
                ),
                @CheckConstraint(
                        name = "ck_ticket_types_price_non_negative",
                        constraint = "price >= 0"
                ),
                @CheckConstraint(
                        name = "ck_ticket_types_sale_end_after_sale_start",
                        constraint = "sale_start_at IS NULL OR sale_end_at IS NULL OR sale_end_at > sale_start_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Integer stock;

    @Column(name = "sale_start_at")
    private Instant saleStartAt;

    @Column(name = "sale_end_at")
    private Instant saleEndAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
