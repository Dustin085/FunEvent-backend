package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.PaymentStatusType;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventImageRepository;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.PasswordResetTokenRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 付款流程的 API 測試。
 * <p>
 * 固定資料直接用 repository 建立、token 直接用 JwtService 產生 ——
 * 完整的註冊到下單流程已經有 PurchaseFlowApiTest 在守，這裡重跑一次只是浪費時間。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFlowApiTest {
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("1000.00");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private TicketTypeRepository ticketTypeRepository;
    @Autowired
    private EventImageRepository eventImageRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private UserRepository userRepository;

    private String buyerToken;
    private String otherToken;
    private Long orderId;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventImageRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        User buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("x").name("買家").role(RoleType.USER).build());
        User other = userRepository.save(User.builder()
                .email("other@example.com").passwordHash("x").name("路人").role(RoleType.USER).build());
        buyerToken = jwtService.generateToken(buyer);
        otherToken = jwtService.generateToken(other);

        Order order = orderRepository.save(Order.builder()
                .user(buyer).totalAmount(ORDER_AMOUNT).build());
        orderId = order.getId();
    }

    @Test
    @DisplayName("完整付款流程：建立付款 → 回呼 → 訂單變 PAID → 重送回呼什麼都不改")
    void fullPaymentFlow() throws Exception {
        String merchantTradeNo = createPayment();

        // 模擬金流商的伺服器回呼 —— 刻意不帶 Authorization，這條路徑是 permitAll
        sendCallback(merchantTradeNo, "1000.00", "1")
                .andExpect(status().isOk())
                .andExpect(content().string("1|OK"));

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        Instant firstPaidAt = orderRepository.findById(orderId).orElseThrow().getPaidAt();

        // 重送：一樣要回 1|OK（回錯誤會讓金流商無限重試），但不能改動任何資料
        sendCallback(merchantTradeNo, "1000.00", "1")
                .andExpect(status().isOk())
                .andExpect(content().string("1|OK"));

        assertEquals(firstPaidAt, orderRepository.findById(orderId).orElseThrow().getPaidAt(),
                "重複回呼不該改動 paidAt");
        assertEquals(1, paymentRepository.count(), "不該產生第二筆付款記錄");
    }

    @Test
    @DisplayName("回呼缺少必要欄位（模擬驗簽失敗）回 400")
    void callbackWithMissingFieldsIsRejected() throws Exception {
        mockMvc.perform(post("/api/payments/callback").param("success", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("回呼帶不存在的交易編號回 400")
    void callbackWithUnknownTradeNoIsRejected() throws Exception {
        sendCallback("FENOTEXIST0000", "1000.00", "1")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("回呼金額被竄改：付款標記失敗，訂單維持 PENDING")
    void callbackWithWrongAmountDoesNotPayTheOrder() throws Exception {
        String merchantTradeNo = createPayment();

        // 仍然回成功（我們確實收到了回呼），但不採信這個金額
        sendCallback(merchantTradeNo, "1.00", "1")
                .andExpect(status().isOk());

        assertEquals(PaymentStatusType.FAILED,
                paymentRepository.findAll().getFirst().getStatus());
        assertEquals(OrderStatusType.PENDING,
                orderRepository.findById(orderId).orElseThrow().getStatus(),
                "金額不符時訂單絕不能被標記為已付款");
    }

    @Test
    @DisplayName("金流商回報付款失敗：訂單維持 PENDING")
    void failedPaymentLeavesOrderPending() throws Exception {
        String merchantTradeNo = createPayment();

        sendCallback(merchantTradeNo, "1000.00", "0")
                .andExpect(status().isOk());

        assertEquals(PaymentStatusType.FAILED,
                paymentRepository.findAll().getFirst().getStatus());
        assertEquals(OrderStatusType.PENDING,
                orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("幫別人的訂單建立付款回 404，不洩漏訂單是否存在")
    void payingSomeoneElsesOrderReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": %d}".formatted(orderId)))
                .andExpect(status().isNotFound());

        assertEquals(0, paymentRepository.count());
    }

    @Test
    @DisplayName("已付款的訂單不能再建立付款，回 409")
    void payingAlreadyPaidOrderReturnsConflict() throws Exception {
        String merchantTradeNo = createPayment();
        sendCallback(merchantTradeNo, "1000.00", "1").andExpect(status().isOk());

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": %d}".formatted(orderId)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("未登入不能建立付款，回 401")
    void creatingPaymentRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": %d}".formatted(orderId)))
                .andExpect(status().isUnauthorized());
    }

    // ── 輔助方法 ───────────────────────────────────────────

    private String createPayment() throws Exception {
        String body = mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": %d}".formatted(orderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantTradeNo").exists())
                .andExpect(jsonPath("$.paymentUrl").exists())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.merchantTradeNo");
    }

    /** 金流商是以 form-urlencoded 送回呼，不是 JSON。 */
    private org.springframework.test.web.servlet.ResultActions sendCallback(
            String merchantTradeNo, String amount, String success) throws Exception {
        return mockMvc.perform(post("/api/payments/callback")
                .param("merchantTradeNo", merchantTradeNo)
                .param("amount", amount)
                .param("success", success));
    }
}
