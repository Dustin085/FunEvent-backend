package com.example.funeventbackend.dto.order;

import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 主辦者視角的一筆銷售明細。
 *
 * <p>⚠️ 範圍限定在「這個活動」，不是整筆訂單 ——
 * 一筆訂單可以跨活動下單，直接回 OrderResponse 會把別的主辦者的
 * 銷售資料（totalAmount 與其他 items）一起送出去。
 *
 * <p>⚠️ 刻意不含買家的 email。「通知參加者」的功能還不存在，
 * 真要做時也該是系統代發，而不是把 email 交給主辦者。
 * 最小揭露：需要時再加，而不是先給了以後可能會用到。
 *
 * @param subtotal 這一筆明細的小計。⚠️ 不是訂單總額
 */
public record EventOrderItemResponse(
        Long orderId,
        Long orderItemId,
        String buyerName,
        String ticketTypeName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal,
        OrderStatusType orderStatus,
        Instant orderedAt
) {
    public static EventOrderItemResponse from(OrderItem orderItem) {
        return new EventOrderItemResponse(
                orderItem.getOrder().getId(),
                orderItem.getId(),
                orderItem.getOrder().getUser().getName(),
                // 名稱與單價取快照欄位，票種日後被改動不影響已成立的訂單
                orderItem.getTicketTypeName(),
                orderItem.getUnitPrice(),
                orderItem.getQuantity(),
                orderItem.getUnitPrice()
                        .multiply(BigDecimal.valueOf(orderItem.getQuantity())),
                orderItem.getOrder().getStatus(),
                orderItem.getOrder().getCreatedAt()
        );
    }
}
