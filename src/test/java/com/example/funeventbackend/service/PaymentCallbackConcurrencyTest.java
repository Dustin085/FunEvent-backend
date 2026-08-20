package com.example.funeventbackend.service;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.PaymentStatusType;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.payment.PaymentCallbackOutcome;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 付款回呼的冪等性測試。
 * <p>
 * 真實金流商在沒收到成功回應時會重送，而且可能同時送達。
 * 這裡模擬同一筆回呼同時抵達 10 次，驗證只有一個真的完成付款。
 * <p>
 * ⚠️ 一樣不能加 @Transactional：子執行緒各自開交易，看不到未提交的測試資料。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentCallbackConcurrencyTest {
    private static final int THREAD_COUNT = 10;
    private static final String MERCHANT_TRADE_NO = "FETESTCONCURRENT1";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Payment payment;
    private Order order;

    @BeforeEach
    void setUp() {
        // 依外鍵相依順序由子到父清空。payments 參照 orders，要排在它前面
        databaseCleaner.clean();

        User buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("x").name("買家").role(RoleType.USER).build());
        order = orderRepository.save(Order.builder()
                .user(buyer).totalAmount(new BigDecimal("1000.00"))
                .expiresAt(Instant.now().plusSeconds(900)).build());
        payment = paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(MERCHANT_TRADE_NO)
                .amount(new BigDecimal("1000.00"))
                .build());
    }

    @Test
    @DisplayName("同一筆回呼同時送達 10 次，只有一次真的完成付款")
    void duplicateCallbacksAreIdempotent() throws InterruptedException {
        Map<String, String> params = Map.of(
                "merchantTradeNo", MERCHANT_TRADE_NO,
                "amount", "1000.00",
                "success", "1");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(THREAD_COUNT);
        Queue<PaymentCallbackOutcome> outcomes = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    outcomes.add(paymentService.handleCallback(params));
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

        failures.forEach(t -> System.out.println(
                "[失敗] " + t.getClass().getName() + " : " + t.getMessage()));
        assertEquals(0, failures.size(), "回呼不該丟出任何例外");

        // ⭐ 核心斷言：沒有回傳值的話，壞掉的實作和正確的實作最終狀態一模一樣，根本測不出來
        assertEquals(1, outcomes.stream().filter(o -> o == PaymentCallbackOutcome.APPLIED).count(),
                "只能有一次回呼真的完成付款");
        assertEquals(THREAD_COUNT - 1,
                outcomes.stream().filter(o -> o == PaymentCallbackOutcome.DUPLICATE).count(),
                "其餘都必須被判定為重複");

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatusType.SUCCESS, reloadedPayment.getStatus());
        assertNotNull(reloadedPayment.getPaidAt());
        assertNotNull(reloadedPayment.getGatewayTradeNo());

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatusType.PAID, reloadedOrder.getStatus());
        assertNotNull(reloadedOrder.getPaidAt());
    }
}
