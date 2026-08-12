package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.dto.order.OrderResponse;
import com.example.funeventbackend.exception.InsufficientStockException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {
    private static final String TICKET_TYPE_NOT_FOUND_MESSAGE = "找不到部分票種";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final TicketTypeRepository ticketTypeRepository;

    @Transactional
    public OrderResponse create(User user, CreateOrderRequest dto) {
        Map<Long, Integer> quantityByTicketTypeId = dto.items().stream()
                .collect(Collectors.toMap(
                        CreateOrderRequest.Item::ticketTypeId,
                        CreateOrderRequest.Item::quantity));

        // 一次撈齊，並依 id 排序：讓所有交易以相同順序取得列鎖，避免死鎖
        List<TicketType> ticketTypes =
                ticketTypeRepository.findAllByIdInWithEvent(quantityByTicketTypeId.keySet())
                        .stream()
                        .sorted(Comparator.comparing(TicketType::getId))
                        .toList();
        if (ticketTypes.size() != quantityByTicketTypeId.size()) {
            throw new ResourceNotFoundException(TICKET_TYPE_NOT_FOUND_MESSAGE);
        }

        Instant now = Instant.now();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (TicketType ticketType : ticketTypes) {
            int quantity = quantityByTicketTypeId.get(ticketType.getId());
            validatePurchasable(ticketType, now);

            // 條件式 UPDATE，回傳 0 代表庫存不足；
            // 此時整筆交易回滾，先前已扣掉的庫存會自動還原
            int updatedRows = ticketTypeRepository.decreaseStock(ticketType.getId(), quantity);
            if (updatedRows == 0) {
                throw new InsufficientStockException("「" + ticketType.getName() + "」剩餘票券不足");
            }

            BigDecimal unitPrice = ticketType.getPrice();
            totalAmount = totalAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            orderItems.add(OrderItem.builder()
                    .ticketType(ticketType)
                    // 名稱與單價快照：票種日後被改動也不影響已成立的訂單
                    .ticketTypeName(ticketType.getName())
                    .unitPrice(unitPrice)
                    .quantity(quantity)
                    .build());
        }

        Order order = orderRepository.save(Order.builder()
                .user(user)
                .totalAmount(totalAmount)
                .status(OrderStatusType.PENDING)
                .build());
        orderItems.forEach(orderItem -> orderItem.setOrder(order));

        return OrderResponse.from(order, orderItemRepository.saveAll(orderItems));
    }

    private void validatePurchasable(TicketType ticketType, Instant now) {
        Event event = ticketType.getEvent();
        // 未公開的活動不該被購買，也不洩漏它的存在
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ResourceNotFoundException(TICKET_TYPE_NOT_FOUND_MESSAGE);
        }
        if (event.getStartAt().isBefore(now)) {
            throw new InvalidStateTransitionException("「" + event.getName() + "」已開始，無法購票");
        }
        if (ticketType.getSaleStartAt() != null && now.isBefore(ticketType.getSaleStartAt())) {
            throw new InvalidStateTransitionException("「" + ticketType.getName() + "」尚未開始販售");
        }
        if (ticketType.getSaleEndAt() != null && now.isAfter(ticketType.getSaleEndAt())) {
            throw new InvalidStateTransitionException("「" + ticketType.getName() + "」已結束販售");
        }
    }
}
