package com.example.funeventbackend.dto.order;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.TicketType;

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
            /** 用來組連結回活動頁 */
            Long eventId,
            /**
             * ⚠️ 活動名稱用「目前的」而不是快照。
             * ticketTypeName / unitPrice 做快照是因為票種常被編輯甚至刪除，
             * 而且單價牽涉金流正確性；活動名稱不同 ——
             * 訂單頁會連到活動頁，兩邊顯示不同名字反而讓人困惑。
             */
            String eventName,
            Long ticketTypeId,
            String ticketTypeName,
            BigDecimal unitPrice,
            Integer quantity,
            BigDecimal subtotal
    ) {
        public static Item from(OrderItem orderItem) {
            // ⚠️ 這兩層都是 LAZY 代理，取 getName() 會觸發初始化。
            // TicketType 與 Event 的類別層級 @BatchSize 把 N 句併成兩句，
            // 沒有它這裡就是 1+N+N
            TicketType ticketType = orderItem.getTicketType();
            Event event = ticketType.getEvent();
            return new Item(
                    orderItem.getId(),
                    event.getId(),
                    event.getName(),
                    ticketType.getId(),
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
