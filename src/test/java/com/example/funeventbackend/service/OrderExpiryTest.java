package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.dto.order.OrderResponse;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.payment.FakePaymentGateway;
import com.example.funeventbackend.payment.PaymentQueryResult;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.PaymentRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
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
    private FakePaymentGateway fakePaymentGateway;
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
    @DisplayName("⭐ 已過期但排程還沒處理的訂單：付款會被 markPaid 自己擋下來")
    void expiredOrderCannotBePaidEvenIfSchedulerHasNotRunYet() {
        Long orderId = createExpiredOrder();
        // ⚠️ 刻意不呼叫 cancelExpiredOrder —— 模擬排程間隔拉長、
        // 還沒輪到處理這筆訂單的那段空窗期：狀態依然是 PENDING
        assertEquals(OrderStatusType.PENDING, currentStatus(orderId));

        String merchantTradeNo = "FETESTEXPIRED001";
        BigDecimal amount = ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY));
        paymentRepository.save(Payment.builder()
                .order(orderRepository.findById(orderId).orElseThrow())
                .merchantTradeNo(merchantTradeNo)
                .amount(amount)
                // 這裡要單獨測「訂單自己的期限」擋下付款，
                // 給 Payment 一個還沒過期的值，避免跟它自己的期限混在一起
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build());

        // ⚠️ 不直接呼叫 orderRepository.markPaid()：@Modifying 查詢需要
        // 一個進行中的交易，這個測試刻意不是交易性的。走 handleCallback
        // 才是真實付款回呼會走的路徑，它自己是 @Transactional
        paymentService.handleCallback(Map.of(
                "merchantTradeNo", merchantTradeNo,
                "amount", amount.toPlainString(),
                "success", "1"));

        assertEquals(OrderStatusType.PENDING, currentStatus(orderId),
                "已過期的訂單不該被付款回呼轉成 PAID，不管排程跑了沒");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "庫存不該因為這次被拒絕的付款而變動——要等排程正式取消才回補");
    }

    @Test
    @DisplayName("⭐ 訂單過期但有一筆還沒過期的付款：不會出現在可取消名單裡")
    void expiredOrderWithLivePaymentIsNotScanned() {
        Long orderId = createExpiredOrder();
        paymentRepository.save(Payment.builder()
                .order(orderRepository.findById(orderId).orElseThrow())
                .merchantTradeNo("FETESTLIVE001")
                .amount(ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY)))
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build());

        List<Long> expired = orderRepository.findExpiredPendingIds(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertFalse(expired.contains(orderId), "還有活著的付款，不該被排程取消");
    }

    @Test
    @DisplayName("⭐ 訂單過期、付款也過期了：出現在可取消名單裡")
    void expiredOrderWithExpiredPaymentIsScanned() {
        Long orderId = createExpiredOrder();
        paymentRepository.save(Payment.builder()
                .order(orderRepository.findById(orderId).orElseThrow())
                .merchantTradeNo("FETESTDEAD001")
                .amount(ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY)))
                // 付款自己的期限也已經過了 —— 使用者真的放棄了
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());

        List<Long> expired = orderRepository.findExpiredPendingIds(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100));

        assertTrue(expired.contains(orderId), "連付款自己的期限都過了，該回收庫存");
    }

    @Test
    @DisplayName("⭐ initiate 發現訂單過期、沒有活著的付款：立刻取消回補庫存，不用等排程")
    void initiateCancelsExpiredOrderImmediatelyWhenNoLivePayment() {
        Long orderId = createExpiredOrder();

        assertThrows(InvalidStateTransitionException.class,
                () -> paymentService.initiate(buyer, orderId));

        assertEquals(OrderStatusType.CANCELLED, currentStatus(orderId));
        assertEquals(INITIAL_STOCK, currentStock(), "應該立刻回補，不用等排程跑到");
    }

    @Test
    @DisplayName("⭐ initiate 發現訂單過期、但有活著的付款：只拒絕這次請求，不取消訂單")
    void initiateDoesNotCancelExpiredOrderWhenLivePaymentExists() {
        Long orderId = createExpiredOrder();
        paymentRepository.save(Payment.builder()
                .order(orderRepository.findById(orderId).orElseThrow())
                .merchantTradeNo("FETESTLIVE002")
                .amount(ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY)))
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build());

        assertThrows(InvalidStateTransitionException.class,
                () -> paymentService.initiate(buyer, orderId));

        // ⚠️ 這裡是整個設計最重要的斷言：訂單不該被取消，
        // 因為那筆先前的付款還有機會成功 —— 貿然取消會重演
        // 「付完款才發現票被還回去」的問題
        assertEquals(OrderStatusType.PENDING, currentStatus(orderId),
                "有活著的付款嘗試，不該被 initiate 順手取消");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "庫存不該被回補，那筆付款可能還會成功");
    }

    @Test
    @DisplayName("⭐ initiate 發現訂單過期、付款也過期了，但綠界說其實已付款：不取消，直接補上成功付款")
    void initiateAppliesPaymentInsteadOfCancellingWhenGatewayReportsPaid() {
        Long orderId = createOrder();
        Order order = orderRepository.findById(orderId).orElseThrow();
        // 留出比較大的過期窗口，讓 paidAt 有空間放在「訂單過期之前」
        order.setExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        orderRepository.save(order);

        String merchantTradeNo = "FETESTINITRECON001";
        BigDecimal amount = ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY));
        paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(merchantTradeNo)
                .amount(amount)
                // 這筆付款自己的期限也過了，existsLivePaymentForOrder 會回傳
                // false —— 這正是這個測試要驗證的情境：光看本機資料不夠，
                // initiate 必須也透過 PaymentReconciliationService 問一次綠界
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());
        // ⚠️ paidAt 設在訂單過期「之前」——模擬使用者其實準時付款成功，
        // 只是回呼漏接，使用者現在又點了一次付款，initiate 才有機會發現
        fakePaymentGateway.registerQueryResult(new PaymentQueryResult(
                merchantTradeNo, "FAKE-GW-INIT-001", true, amount,
                Instant.now().minus(11, ChronoUnit.MINUTES)));

        assertThrows(InvalidStateTransitionException.class,
                () -> paymentService.initiate(buyer, orderId));

        assertEquals(OrderStatusType.PAID, currentStatus(orderId),
                "綠界說已經付款，initiate 不該取消，要直接補上成功付款");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "訂單變成 PAID，票不該被還回去");
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
