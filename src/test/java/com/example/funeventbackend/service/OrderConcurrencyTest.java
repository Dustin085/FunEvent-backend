package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搶票併發測試。
 * <p>
 * ⚠️ 這個類別「絕對不能」加 @Transactional：
 * 測試的交易永遠不會提交，子執行緒各自開的交易根本看不到測試資料，
 * 整個併發情境會變成假的。代價是資料不會自動回滾，所以要自己清。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderConcurrencyTest {
    // 執行緒數刻意大於 2：只開兩條的話，就算程式碼有 TOCTOU 也可能剛好錯開而測不出來
    private static final int THREAD_COUNT = 10;

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
    private OrderItemRepository orderItemRepository;

    private User buyer;
    private TicketType lastTicket;

    @BeforeEach
    void setUp() {
        // 依外鍵相依順序由子到父清空
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        userRepository.deleteAll();

        User seller = userRepository.save(User.builder()
                .email("seller@example.com")
                .passwordHash("$2a$10$dummy")
                .name("賣家")
                .role(RoleType.USER)
                .build());
        buyer = userRepository.save(User.builder()
                .email("buyer@example.com")
                .passwordHash("$2a$10$dummy")
                .name("買家")
                .role(RoleType.USER)
                .build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller)
                .name("測試主辦")
                .build());
        Event event = eventRepository.save(Event.builder()
                .organizer(organizer)
                .name("測試活動")
                .description("併發測試用")
                .startAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(31, ChronoUnit.DAYS))
                .category(Category.LIFE_EXPERIENCE)
                .city(City.TAIPEI)
                .district("大安區")
                .status(EventStatus.PUBLISHED)
                .build());
        lastTicket = ticketTypeRepository.save(TicketType.builder()
                .event(event)
                .name("最後一張")
                .price(new BigDecimal("500.00"))
                .capacity(1)
                .stock(1)
                .build());
    }

    @Test
    @DisplayName("多人同時搶最後一張票，只有一個人成功，庫存不會變成負數")
    void onlyOneBuyerGetsTheLastTicket() throws InterruptedException {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(lastTicket.getId(), 1)));

        // startGate：讓所有執行緒先在同一條起跑線集合，最大化真正的競爭
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderService.create(buyer, request);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(finishGate.await(30, TimeUnit.SECONDS), "測試逾時，可能發生死鎖");
        executor.shutdown();

        // 觀察用：印出失敗者實際丟的例外，確認 H2 是「重新評估條件」還是「丟併發例外」
        failures.forEach(t -> System.out.println(
                "[失敗] " + t.getClass().getName() + " : " + t.getMessage()));

        assertEquals(1, successCount.get(), "只能有一個人買到");
        assertEquals(THREAD_COUNT - 1, failures.size(), "其餘所有人都必須失敗");
        assertEquals(0, ticketTypeRepository.findById(lastTicket.getId()).orElseThrow().getStock(),
                "庫存必須是 0，絕不能是負數");
        assertEquals(1, orderRepository.count(), "只能產生一張訂單");
        assertEquals(1, orderItemRepository.count(), "只能產生一筆訂單項目");
    }
}
