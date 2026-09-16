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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程取消訂單前，先主動查詢金流商確認有沒有漏接的付款成功回呼。
 *
 * <p>⚠️ {@code OrderExpiryScheduler} 在測試環境是關掉的（見 application-test.yaml，
 * 排程在測試跑到一半動資料會讓斷言隨機失敗），所以這裡自己 new 一份出來——
 * 傳進去的 orderRepository／paymentReconciliationService 是 Spring 管理的真正
 * bean，{@code @Transactional} 依然正常生效，跟排程真的跑起來時完全一樣，
 * 只是觸發的時間點由測試自己控制。
 *
 * <p>⚠️ 不加 @Transactional：條件式 UPDATE 與後續讀取要看到彼此真的提交的結果。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryReconciliationTest {
    private static final int INITIAL_STOCK = 10;
    private static final int BUY_QUANTITY = 3;

    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentReconciliationService paymentReconciliationService;
    @Autowired
    private OrderRepository orderRepository;
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
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private OrderExpiryScheduler scheduler;
    private User buyer;
    private TicketType ticketType;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        scheduler = new OrderExpiryScheduler(orderRepository, paymentReconciliationService);

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
                .description("排程查詢測試用")
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

    /** 建一筆過期訂單 + 一筆自己期限也過期的 PENDING 付款（模擬使用者放著沒管） */
    private Long createExpiredOrderWithExpiredPayment(String merchantTradeNo) {
        OrderResponse response = orderService.create(buyer, new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(ticketType.getId(), BUY_QUANTITY))));
        Long orderId = response.id();

        Order order = orderRepository.findById(orderId).orElseThrow();
        // 留出比較大的過期窗口，讓「查到已付款」的測試案例有空間放一個
        // 「早在訂單過期之前就已經付款完成」的 paidAt
        order.setExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        orderRepository.save(order);

        paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(merchantTradeNo)
                .amount(ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY)))
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());
        return orderId;
    }

    private OrderStatusType currentStatus(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private int currentStock() {
        return ticketTypeRepository.findById(ticketType.getId()).orElseThrow().getStock();
    }

    @Test
    @DisplayName("查詢綠界回報未付款：正常取消回補庫存")
    void cancelsWhenGatewayReportsUnpaid() {
        String merchantTradeNo = "FETESTRECON001";
        Long orderId = createExpiredOrderWithExpiredPayment(merchantTradeNo);
        // 不呼叫 registerQueryResult —— FakePaymentGateway 查不到任何結果，
        // queryStatus 回傳 Optional.empty()。這裡先驗證「查不到」跟「查到未付款」
        // 是不同情境：查不到要保守不取消，所以特地也註冊一筆「未付款」的結果
        fakePaymentGateway.registerQueryResult(
                new PaymentQueryResult(merchantTradeNo, null, false, BigDecimal.ZERO, null));

        scheduler.cancelExpiredOrders();

        assertEquals(OrderStatusType.CANCELLED, currentStatus(orderId));
        assertEquals(INITIAL_STOCK, currentStock(), "確認真的沒付款，應該回補庫存");
    }

    @Test
    @DisplayName("⭐ 查詢綠界回報其實已付款：不取消，直接補上成功付款（回呼可能漏接）")
    void doesNotCancelWhenGatewayReportsPaid() {
        String merchantTradeNo = "FETESTRECON002";
        Long orderId = createExpiredOrderWithExpiredPayment(merchantTradeNo);
        BigDecimal amount = ticketType.getPrice().multiply(BigDecimal.valueOf(BUY_QUANTITY));
        // ⚠️ paidAt 刻意設在訂單過期「之前」——模擬使用者其實準時付款成功，
        // 只是回呼漏接，現在才被查詢揭露。如果這裡誤用「現在」，
        // markPaid 的 expiresAt 檢查會把這筆明明準時的付款誤判成逾期
        fakePaymentGateway.registerQueryResult(new PaymentQueryResult(
                merchantTradeNo, "FAKE-GW-001", true, amount,
                Instant.now().minus(6, ChronoUnit.MINUTES)));

        scheduler.cancelExpiredOrders();

        assertEquals(OrderStatusType.PAID, currentStatus(orderId),
                "綠界說已經付款，不該被取消，要直接補上成功付款");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "訂單變成 PAID，票不該被還回去");
    }

    @Test
    @DisplayName("⭐ 查詢本身失敗（查不到結果）：保守地這次先不取消")
    void doesNotCancelWhenQueryFails() {
        // 刻意不 registerQueryResult —— FakePaymentGateway 對任何未註冊的
        // merchantTradeNo 一律回傳 Optional.empty()，模擬查詢失敗／查無結果
        Long orderId = createExpiredOrderWithExpiredPayment("FETESTRECON003");

        scheduler.cancelExpiredOrders();

        assertEquals(OrderStatusType.PENDING, currentStatus(orderId),
                "查詢失敗不能當成「查到了、沒付款」，這次不該取消");
        assertEquals(INITIAL_STOCK - BUY_QUANTITY, currentStock(),
                "沒有取消，庫存不該被回補");
    }

    @Test
    @DisplayName("沒有任何付款嘗試的過期訂單：跳過查詢，直接照舊取消")
    void cancelsNormallyWhenNoPaymentWasEverMade() {
        OrderResponse response = orderService.create(buyer, new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(ticketType.getId(), BUY_QUANTITY))));
        Long orderId = response.id();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        orderRepository.save(order);

        scheduler.cancelExpiredOrders();

        assertEquals(OrderStatusType.CANCELLED, currentStatus(orderId));
        assertEquals(INITIAL_STOCK, currentStock());
    }

    @Test
    @DisplayName("findExpiredPendingIds 撈到的訂單，排程真的能處理完")
    void schedulerHandlesExactlyWhatWasScanned() {
        Long orderId = createExpiredOrderWithExpiredPayment("FETESTRECON004");
        fakePaymentGateway.registerQueryResult(
                new PaymentQueryResult("FETESTRECON004", null, false, BigDecimal.ZERO, null));

        List<Long> scanned = orderRepository.findExpiredPendingIds(
                Instant.now(), PageRequest.of(0, 100));
        assertTrue(scanned.contains(orderId));

        scheduler.cancelExpiredOrders();

        assertEquals(OrderStatusType.CANCELLED, currentStatus(orderId));
    }
}
