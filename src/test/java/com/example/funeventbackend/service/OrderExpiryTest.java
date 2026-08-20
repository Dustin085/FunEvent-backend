package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.dto.order.OrderResponse;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 訂單逾時取消與庫存回補。
 *
 * <p>沒有這套機制的話，使用者建了訂單卻不付款，那些票會被永久鎖住 ——
 * 活動可以「賣完」但實際上一張都沒賣出去。
 *
 * <p>⚠️ 不加 @Transactional：條件式 UPDATE 與後續讀取要看到彼此真的提交的結果。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryTest {
    private static final int INITIAL_STOCK = 10;
    private static final int BUY_QUANTITY = 3;

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private TicketTypeRepository ticketTypeRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private User buyer;
    private TicketType ticketType;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        User seller = userRepository.save(User.builder()
                .email("seller@example.com").passwordHash("$2a$10$dummy")
                .name("賣家").role(RoleType.USER).build());
        buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("$2a$10$dummy")
                .name("買家").role(RoleType.USER).build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller).name("測試主辦").build());
        Event event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("測試活動")
                .description("逾時取消測試用")
                .startAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(31, ChronoUnit.DAYS))
                .category(Category.LIFE_EXPERIENCE)
                .city(City.TAIPEI)
                .district("大安區")
                .status(EventStatus.PUBLISHED)
                .build());
        ticketType = ticketTypeRepository.save(TicketType.builder()
                .event(event)
                .name("一般票")
                .price(new BigDecimal("500.00"))
                .capacity(INITIAL_STOCK)
                .stock(INITIAL_STOCK)
                .build());
    }

    /** 建一筆真的訂單（會扣庫存），再把期限改成過去，模擬「放著沒付款」 */
    private Long createExpiredOrder() {
        Long orderId = createOrder();
        expire(orderId);
        return orderId;
    }

    private Long createOrder() {
        OrderResponse response = orderService.create(buyer, new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(ticketType.getId(), BUY_QUANTITY))));
        return response.id();
    }

    private void expire(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        orderRepository.save(order);
    }

    private int currentStock() {
        return ticketTypeRepository.findById(ticketType.getId()).orElseThrow().getStock();
    }

    private OrderStatusType currentStatus(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("逾時未付款的訂單會被取消，庫存回補")
    void cancelsExpiredOrderAndRestoresStock() {
        Long orderId = createExpiredOrder();
        // 建單當下就扣掉了
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock());

        assertTrue(orderService.cancelExpiredOrder(orderId));

        assertEquals(OrderStatusType.CANCELLED, currentStatus(orderId));
        assertEquals(INITIAL_STOCK, currentStock(), "庫存應該完全回補");
    }

    @Test
    @DisplayName("⭐ 同一筆重複取消：庫存只會回補一次")
    void restoringStockIsIdempotent() {
        Long orderId = createExpiredOrder();

        assertTrue(orderService.cancelExpiredOrder(orderId), "第一次應該成功");
        assertFalse(orderService.cancelExpiredOrder(orderId), "第二次應該什麼都不做");
        assertFalse(orderService.cancelExpiredOrder(orderId), "第三次也一樣");

        // ⚠️ 沒有 markCancelled 的狀態條件把關的話，這裡會變成 16（超過 capacity），
        // 而 ck_ticket_types_stock_within_capacity 會先炸掉 —— 兩種都是失敗
        assertEquals(INITIAL_STOCK, currentStock(), "庫存被回補了不只一次");
    }

    @Test
    @DisplayName("已付款的訂單就算過了期限也不會被取消")
    void doesNotCancelPaidOrder() {
        Long orderId = createExpiredOrder();
        // 在逾時之後才付款成功 —— 現實中就是「使用者拖到最後一秒」。
        // ⚠️ 這裡不能直接呼叫 orderRepository.markPaid()：@Modifying 查詢需要
        // 一個進行中的交易，而這個測試刻意不是交易性的。save() 自己帶交易
        Order paid = orderRepository.findById(orderId).orElseThrow();
        paid.setStatus(OrderStatusType.PAID);
        paid.setPaidAt(Instant.now());
        orderRepository.save(paid);

        assertFalse(orderService.cancelExpiredOrder(orderId));

        assertEquals(OrderStatusType.PAID, currentStatus(orderId));
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "已付款的訂單不該把票還回去");
    }

    @Test
    @DisplayName("還沒到期的訂單不會出現在掃描結果裡")
    void doesNotScanUnexpiredOrder() {
        Long orderId = createOrder();

        List<Long> expired = orderRepository.findExpiredPendingIds(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertFalse(expired.contains(orderId));
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock());
    }

    @Test
    @DisplayName("掃描只會撈出 PENDING 且已過期的訂單")
    void scanFindsOnlyExpiredPendingOrders() {
        Long expiredOrderId = createExpiredOrder();
        Long freshOrderId = createOrder();

        List<Long> expired = orderRepository.findExpiredPendingIds(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertTrue(expired.contains(expiredOrderId));
        assertFalse(expired.contains(freshOrderId));
    }
}
