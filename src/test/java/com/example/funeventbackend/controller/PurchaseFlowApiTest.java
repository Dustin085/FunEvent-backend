package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.PasswordResetTokenRepository;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 購買流程的 API 測試：註冊 → 登入 → 建立主辦者 → 建立活動 → 建立票種 → 發布 → 下單。
 * <p>
 * 跟 OrderConcurrencyTest 的分工：
 * 那個直接呼叫 Service，驗的是「扣庫存的原子性」；
 * 這個走完整的 HTTP 流程，驗的是 Security filter、@Valid、GlobalExceptionHandler、
 * Jackson 序列化這些 Service 層測不到的東西。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseFlowApiTest {
    private static final String ORGANIZER_EMAIL = "organizer@example.com";
    private static final String BUYER_EMAIL = "buyer@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
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
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @BeforeEach
    void setUp() {
        // 沒有 @Transactional 就沒有自動回滾，依外鍵相依順序由子到父清空。
        // 登入會寫入 refresh_tokens，所以它也要在 users 之前清掉。
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("完整購買流程：註冊 → 登入 → 建組織 → 建活動 → 建票種 → 發布 → 下單")
    void fullPurchaseFlow() throws Exception {
        // ── 1. 註冊主辦者 ──────────────────────────────────
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(ORGANIZER_EMAIL, "主辦者")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(ORGANIZER_EMAIL))
                // 密碼雜湊絕對不能出現在回應裡
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // ── 2. 登入拿 access token ─────────────────────────
        String organizerToken = login(ORGANIZER_EMAIL);

        // ── 3. 成為主辦者 ──────────────────────────────────
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "測試主辦單位", "introduction": "端到端測試" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("測試主辦單位"));

        // 重複建立主辦者要回 409
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "第二個主辦單位" }
                                """))
                .andExpect(status().isConflict());

        // ── 4. 建立活動（預設 DRAFT）────────────────────────
        String eventBody = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long eventId = readLong(eventBody, "$.id");

        // 還沒發布，公開查詢應該看不到（用 404 而不是 403，不洩漏存在性）
        mockMvc.perform(get("/api/events/{id}", eventId))
                .andExpect(status().isNotFound());

        // 沒有票種時不能發布
        mockMvc.perform(patch("/api/events/{id}/publish", eventId)
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isConflict());

        // ── 5. 建立票種 ────────────────────────────────────
        String ticketTypeBody = mockMvc.perform(post("/api/events/{id}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "早鳥票",
                                  "description": "限量優惠",
                                  "price": 500.00,
                                  "capacity": 10
                                }
                                """))
                .andExpect(status().isCreated())
                // stock 由後端設成 capacity，客戶端沒有送這個欄位
                .andExpect(jsonPath("$.stock").value(10))
                .andReturn().getResponse().getContentAsString();
        long ticketTypeId = readLong(ticketTypeBody, "$.id");

        // ── 6. 發布活動 ────────────────────────────────────
        mockMvc.perform(patch("/api/events/{id}/publish", eventId)
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 發布後未登入者也看得到活動與票種
        mockMvc.perform(get("/api/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizer.name").value("測試主辦單位"));
        mockMvc.perform(get("/api/events/{id}/ticket-types", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("早鳥票"));

        // 重複發布要回 409
        mockMvc.perform(patch("/api/events/{id}/publish", eventId)
                        .header("Authorization", "Bearer " + organizerToken))
                .andExpect(status().isConflict());

        // ── 7. 買家下單 ────────────────────────────────────
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(BUYER_EMAIL, "買家")))
                .andExpect(status().isCreated());
        String buyerToken = login(BUYER_EMAIL);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(ticketTypeId, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(1500.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ticketTypeName").value("早鳥票"))
                .andExpect(jsonPath("$.items[0].subtotal").value(1500.00));

        // ── 8. 庫存確實被扣掉 ──────────────────────────────
        mockMvc.perform(get("/api/events/{id}/ticket-types", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock").value(7));

        assertEquals(1, orderRepository.count());
        assertEquals(1, orderItemRepository.count());
    }

    @Test
    @DisplayName("更新活動的變更確實寫進資料庫（沒有 save() 也要靠髒檢查落地）")
    void updatingEventPersistsWithoutExplicitSave() throws Exception {
        String token = registerAndLogin(ORGANIZER_EMAIL, "主辦者");
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "測試主辦單位" }
                                """))
                .andExpect(status().isCreated());
        String eventBody = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long eventId = readLong(eventBody, "$.id");

        mockMvc.perform(put("/api/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "改過的活動名稱",
                                  "description": "改過的介紹",
                                  "startAt": "%s",
                                  "endAt": "%s",
                                  "locationName": "改過的場地",
                                  "address": "改過的地址"
                                }
                                """.formatted(
                                Instant.now().plus(40, ChronoUnit.DAYS),
                                Instant.now().plus(41, ChronoUnit.DAYS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改過的活動名稱"));

        // 直接讀資料庫，不看回應 —— 回應可能只反映記憶體中的物件，
        // 真正要驗的是交易提交後 DB 裡的值
        Event reloaded = eventRepository.findById(eventId).orElseThrow();
        assertEquals("改過的活動名稱", reloaded.getName());
        assertEquals("改過的介紹", reloaded.getDescription());
        assertEquals("改過的場地", reloaded.getLocationName());
        assertEquals("改過的地址", reloaded.getAddress());
    }

    @Test
    @DisplayName("未帶 token 下單回 401")
    void orderWithoutTokenReturnsUnauthorized() throws Exception {
        long ticketTypeId = prepareOnSaleTicketType(10);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(ticketTypeId, 1)))
                .andExpect(status().isUnauthorized());

        assertEquals(0, orderRepository.count());
    }

    @Test
    @DisplayName("下單數量超過庫存回 409，且不留下任何訂單")
    void orderExceedingStockReturnsConflict() throws Exception {
        long ticketTypeId = prepareOnSaleTicketType(2);
        String buyerToken = registerAndLogin(BUYER_EMAIL, "買家");

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(ticketTypeId, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());

        // 交易回滾，庫存不變、沒有半成品訂單
        assertEquals(2, ticketTypeRepository.findById(ticketTypeId).orElseThrow().getStock());
        assertEquals(0, orderRepository.count());
        assertEquals(0, orderItemRepository.count());
    }

    @Test
    @DisplayName("同一個票種重複出現回 400，並指出是哪個欄位")
    void orderWithDuplicateTicketTypeReturnsBadRequest() throws Exception {
        long ticketTypeId = prepareOnSaleTicketType(10);
        String buyerToken = registerAndLogin(BUYER_EMAIL, "買家");

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    { "ticketTypeId": %d, "quantity": 1 },
                                    { "ticketTypeId": %d, "quantity": 2 }
                                  ]
                                }
                                """.formatted(ticketTypeId, ticketTypeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ticketTypeIdUnique"));

        assertEquals(0, orderRepository.count());
    }

    @Test
    @DisplayName("別人的活動不能改：已發布的回 403，草稿的回 404")
    void updatingSomeoneElsesEventIsRejected() throws Exception {
        String ownerToken = registerAndLogin(ORGANIZER_EMAIL, "主辦者");
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "測試主辦單位" }
                                """))
                .andExpect(status().isCreated());
        String draftBody = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long draftEventId = readLong(draftBody, "$.id");

        // 另一位主辦者
        String otherToken = registerAndLogin("other@example.com", "別的主辦者");
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "別的主辦單位" }
                                """))
                .andExpect(status().isCreated());

        // 草稿活動：連存在都不該讓他知道
        mockMvc.perform(patch("/api/events/{id}/publish", draftEventId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ── 輔助方法 ───────────────────────────────────────────

    private String registerAndLogin(String email, String name) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, name)))
                .andExpect(status().isCreated());
        return login(email);
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "%s" }
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** 建立一個已發布、正在販售中的票種，回傳它的 id。 */
    private long prepareOnSaleTicketType(int capacity) throws Exception {
        String token = registerAndLogin(ORGANIZER_EMAIL, "主辦者");
        mockMvc.perform(post("/api/organizers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "測試主辦單位" }
                                """))
                .andExpect(status().isCreated());

        String eventBody = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long eventId = readLong(eventBody, "$.id");

        String ticketTypeBody = mockMvc.perform(post("/api/events/{id}/ticket-types", eventId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "早鳥票", "price": 500.00, "capacity": %d }
                                """.formatted(capacity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(patch("/api/events/{id}/publish", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        return readLong(ticketTypeBody, "$.id");
    }

    private String registerBody(String email, String name) {
        return """
                { "email": "%s", "password": "%s", "name": "%s" }
                """.formatted(email, PASSWORD, name);
    }

    /** 時間一律相對於現在，測試才不會因為寫死日期而在未來過期。 */
    private String createEventBody() {
        return """
                {
                  "name": "測試活動",
                  "description": "端到端測試用",
                  "startAt": "%s",
                  "endAt": "%s",
                  "locationName": "測試場地",
                  "address": "台北市測試路 1 號"
                }
                """.formatted(
                Instant.now().plus(30, ChronoUnit.DAYS),
                Instant.now().plus(31, ChronoUnit.DAYS));
    }

    private String orderBody(long ticketTypeId, int quantity) {
        return """
                { "items": [ { "ticketTypeId": %d, "quantity": %d } ] }
                """.formatted(ticketTypeId, quantity);
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
