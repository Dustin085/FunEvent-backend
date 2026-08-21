package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.dto.order.EventOrderItemResponse;
import com.example.funeventbackend.dto.order.EventSalesByStatus;
import com.example.funeventbackend.dto.order.EventSalesSummary;
import com.example.funeventbackend.dto.order.OrderResponse;
import com.example.funeventbackend.exception.InsufficientStockException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.*;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private static final String TICKET_TYPE_NOT_FOUND_MESSAGE = "找不到部分票種";
    private static final String ORDER_NOT_FOUND_MESSAGE = "找不到此訂單";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final TicketTypeRepository ticketTypeRepository;
    // 主辦者看訂單前要驗擁有權。EventService 不依賴 OrderService，所以沒有循環
    private final EventService eventService;

    /** 建單後多久未付款就自動取消。Boot 會把設定值的 15m 這種寫法解析成 Duration */
    @Value("${app.order.payment-timeout}")
    private Duration paymentTimeout;

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
                // 期限在建單當下就算好並存起來，之後改設定值也不會影響這一筆
                .expiresAt(Instant.now().plus(paymentTimeout))
                .build());
        orderItems.forEach(order::addOrderItem);

        return OrderResponse.from(order, orderItemRepository.saveAll(orderItems));
    }

    /**
     * 取消一筆逾時未付款的訂單並回補庫存。
     *
     * <p>⭐ 冪等的關鍵在第一句：markCancelled 是條件式 UPDATE，
     * 只有把訂單從 PENDING 轉走的那一次會回傳 1。
     * 這和 PaymentService 的 markPaid 是同一招、方向相反 ——
     * 兩邊都只在「贏得狀態轉移」時才動庫存，就不可能既回補又收款。
     *
     * <p>因為仲裁者是資料庫，多實例部署也不需要分散式鎖。
     *
     * @return true 代表這次呼叫真的完成了取消；false 代表訂單已經不是 PENDING
     * （付款成功、或已被別人取消）—— 這不是錯誤，是併發下的正常結果
     */
    @Transactional
    public boolean cancelExpiredOrder(Long orderId) {
        if (orderRepository.markCancelled(orderId) == 0) {
            return false;
        }

        // 只有贏家會走到這裡，所以庫存不會被回補兩次
        for (OrderItem item : orderItemRepository.findByOrderId(orderId)) {
            // getId() 不會觸發 LAZY 代理初始化，所以這裡不需要 JOIN FETCH
            ticketTypeRepository.restoreStock(item.getTicketType().getId(), item.getQuantity());
        }
        log.info("逾時未付款，訂單已取消並回補庫存 orderId={}", orderId);
        return true;
    }

    /**
     * 主辦者視角：某個活動的銷售明細。
     *
     * <p>⭐ 擁有權檢查在最前面。少了它，任何登入使用者帶著別人的 eventId
     * 就能看光那場活動的銷售紀錄與買家姓名。
     */
    @Transactional(readOnly = true)
    public Page<EventOrderItemResponse> findEventOrders(
            User user, Long eventId, OrderStatusType status, Pageable pageable) {
        eventService.getOwnedEntity(user, eventId);

        Page<OrderItem> items = status == null
                ? orderItemRepository.findByEventId(eventId, pageable)
                : orderItemRepository.findByEventIdAndStatus(eventId, status, pageable);
        return items.map(EventOrderItemResponse::from);
    }

    @Transactional(readOnly = true)
    public EventSalesSummary getEventSalesSummary(User user, Long eventId) {
        Event event = eventService.getOwnedEntity(user, eventId);

        long paidQuantity = 0;
        BigDecimal paidAmount = BigDecimal.ZERO;
        long pendingQuantity = 0;

        for (EventSalesByStatus row : orderItemRepository.sumByStatus(eventId)) {
            switch (row.status()) {
                case PAID -> {
                    paidQuantity = row.quantity();
                    paidAmount = row.amount();
                }
                case PENDING -> pendingQuantity = row.quantity();
                // CANCELLED / REFUNDED 不列入銷售 —— 那些票已經還回庫存了
                default -> {
                }
            }
        }
        // ⚠️ 沒有任何訂單時 sumByStatus 回空 List，這裡要給 0 而不是 null
        return new EventSalesSummary(
                event.getName(), paidQuantity, paidAmount, pendingQuantity);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> findByUser(User user, Pageable pageable) {
        return orderRepository.findByUser(user, pageable)
                .map(order -> OrderResponse.from(order, order.getOrderItems()));
    }

    @Transactional(readOnly = true)
    public OrderResponse findByIdAndUser(User user, Long orderId) {
        // 查詢條件已包含 user，所以「訂單不存在」和「訂單不是你的」都會走到這個 404。
        // 訂單是私有資源，回 403 等於證實了這個 id 存在，可被用來探測訂單量。
        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND_MESSAGE));
        return OrderResponse.from(order, order.getOrderItems());
    }

    private void validatePurchasable(TicketType ticketType, Instant now) {
        Event event = ticketType.getEvent();
        // 未公開的活動不該被購買，也不洩漏它的存在
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ResourceNotFoundException(TICKET_TYPE_NOT_FOUND_MESSAGE);
        }
        // ⚠️ 看 endAt 不是 startAt：進行中的活動仍可購票（展覽、長期課程、營隊都是這樣）。
        // 「開演即停售」由票種的 saleEndAt 表達 —— 那是主辦者自己決定的，
        // 不該由系統一刀切成「所有活動都當單場演唱會」。
        // 這條規則與 EventSpecifications.endsAfter 一致，才不會出現「看得到買不到」
        if (event.getEndAt().isBefore(now)) {
            throw new InvalidStateTransitionException("「" + event.getName() + "」已結束，無法購票");
        }
        if (ticketType.getSaleStartAt() != null && now.isBefore(ticketType.getSaleStartAt())) {
            throw new InvalidStateTransitionException("「" + ticketType.getName() + "」尚未開始販售");
        }
        if (ticketType.getSaleEndAt() != null && now.isAfter(ticketType.getSaleEndAt())) {
            throw new InvalidStateTransitionException("「" + ticketType.getName() + "」已結束販售");
        }
    }
}
