package com.example.funeventbackend.dto.order;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        BigDecimal totalAmount,
        OrderStatusType status,
        Instant createdAt,
        List<Item> items
) {
    public record Item(
            Long id,
            Long ticketTypeId,
            String ticketTypeName,
            BigDecimal unitPrice,
            Integer quantity,
            BigDecimal subtotal
    ) {
        public static Item from(OrderItem orderItem) {
            return new Item(
                    orderItem.getId(),
                    orderItem.getTicketType().getId(),
                    // 名稱與單價都取快照欄位，票種日後被改動不影響已成立的訂單
                    orderItem.getTicketTypeName(),
                    orderItem.getUnitPrice(),
                    orderItem.getQuantity(),
                    orderItem.getUnitPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity()))
            );
        }
    }

    public static OrderResponse from(Order order, List<OrderItem> orderItems) {
        return new OrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt(),
                orderItems.stream().map(Item::from).toList()
        );
    }
}
