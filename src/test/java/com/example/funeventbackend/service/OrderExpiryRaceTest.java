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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⭐ 逾時取消 vs 付款回呼的競態。
 *
 * <p>真實世界一定會發生：排程正要取消第 87 號訂單，同一毫秒綠界的回呼進來說付款成功。
 * 處理不好就會變成「錢收了，但票已經還回去賣給別人」。
 *
 * <p>兩側用的是同一招、方向相反的條件式 UPDATE：
 * <pre>
 *   markPaid(id)       WHERE id=? AND status=PENDING  → 回 1 才寫 paidAt
 *   markCancelled(id)  WHERE id=? AND status=PENDING  → 回 1 才回補庫存
 * </pre>
 * 誰贏得那句 UPDATE，誰就有權處理後續。仲裁者是資料庫，
 * 所以多實例部署也不需要分散式鎖。
 *
 * <p>⚠️ 不加 @Transactional：子執行緒各自開交易，看不到未提交的測試資料。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryRaceTest {
    private static final int THREAD_COUNT = 10;
    private static final int INITIAL_STOCK = 10;
    private static final int BUY_QUANTITY = 3;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.00");
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("1500.00");
    private static final String MERCHANT_TRADE_NO = "FETESTRACE0001";

    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;
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
    private PaymentRepository paymentRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private TicketType ticketType;
    private Long orderId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        User seller = userRepository.save(User.builder()
                .email("seller@example.com").passwordHash("$2a$10$dummy")
                .name("賣家").role(RoleType.USER).build());
        User buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("$2a$10$dummy")
                .name("買家").role(RoleType.USER).build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller).name("測試主辦").build());
        Event event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("測試活動")
                .description("競態測試用")
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
                .price(UNIT_PRICE)
                .capacity(INITIAL_STOCK)
                .stock(INITIAL_STOCK)
                .build());

        // 建一筆真的訂單（會扣庫存），再把期限改成過去
        OrderResponse response = orderService.create(buyer, new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(ticketType.getId(), BUY_QUANTITY))));
        orderId = response.id();
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        orderRepository.save(order);

        paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(MERCHANT_TRADE_NO)
                .amount(ORDER_AMOUNT)
                .build());
    }

    @Test
    @DisplayName("⭐ 取消與付款同時發生：最終狀態只有一種，庫存不會既回補又賣出")
    void cancellationAndPaymentCannotBothWin() throws InterruptedException {
        Map<String, String> callbackParams = Map.of(
                "merchantTradeNo", MERCHANT_TRADE_NO,
                "amount", ORDER_AMOUNT.toPlainString(),
                "success", "1");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREAD_COUNT);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            // 一半嘗試取消、一半嘗試完成付款，全部從同一條起跑線出發
            for (int i = 0; i < THREAD_COUNT; i++) {
                boolean cancel = i % 2 == 0;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        if (cancel) {
                            orderService.cancelExpiredOrder(orderId);
                        } else {
                            paymentService.handleCallback(callbackParams);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(finishGate.await(30, TimeUnit.SECONDS), "測試逾時，可能發生死鎖");
        } finally {
            executor.shutdownNow();
        }

        failures.forEach(Throwable::printStackTrace);
        assertTrue(failures.isEmpty(), "不應有例外，但有 " + failures.size() + " 個");

        OrderStatusType finalStatus =
                orderRepository.findById(orderId).orElseThrow().getStatus();
        int finalStock =
                ticketTypeRepository.findById(ticketType.getId()).orElseThrow().getStock();

        // ⭐ 核心：狀態與庫存必須一致，不能出現「已付款但票被還回去」
        if (finalStatus == OrderStatusType.PAID) {
            assertEquals(INITIAL_STOCK - BUY_QUANTITY, finalStock,
                    "訂單已付款，票不該被回補");
        } else {
            assertEquals(OrderStatusType.CANCELLED, finalStatus,
                    "最終狀態只可能是 PAID 或 CANCELLED");
            assertEquals(INITIAL_STOCK, finalStock,
                    "訂單已取消，票應該剛好回補一次");
        }
    }
}
