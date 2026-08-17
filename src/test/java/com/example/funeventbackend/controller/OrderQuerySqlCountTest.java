package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.PasswordResetTokenRepository;
import com.example.funeventbackend.repository.RefreshTokenRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用 Hibernate 的 Statistics 數出每個查詢端點實際發了幾句 SQL。
 * <p>
 * 目的不是效能測試，而是把「有沒有 1+N」變成可自動偵測的東西 ——
 * 光看 log 只能發現當下這一次，測試才能防止它日後悄悄長回來。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderQuerySqlCountTest {
    private static final int ORDER_COUNT = 3;
    private static final int ITEMS_PER_ORDER = 2;
    private static final String BUYER_EMAIL = "buyer@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PasswordEncoder passwordEncoder;
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

    private Long firstOrderId;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        User seller = userRepository.save(User.builder()
                .email("seller@example.com").passwordHash("x").name("賣家").role(RoleType.USER).build());
        User buyer = userRepository.save(User.builder()
                .email(BUYER_EMAIL).passwordHash(passwordEncoder.encode(PASSWORD))
                .name("買家").role(RoleType.USER).build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller).name("測試主辦").build());
        Event event = eventRepository.save(Event.builder()
                .organizer(organizer).name("測試活動").description("SQL 計數用")
                .startAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(31, ChronoUnit.DAYS))
                .category(Category.LIFE_EXPERIENCE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());

        // 兩個票種，讓每張訂單都有多筆明細
        List<TicketType> ticketTypes = new ArrayList<>();
        for (int i = 0; i < ITEMS_PER_ORDER; i++) {
            ticketTypes.add(ticketTypeRepository.save(TicketType.builder()
                    .event(event).name("票種 " + i)
                    .price(new BigDecimal("500.00")).capacity(100).stock(100).build()));
        }

        for (int i = 0; i < ORDER_COUNT; i++) {
            Order order = orderRepository.save(Order.builder()
                    .user(buyer).totalAmount(new BigDecimal("1000.00")).build());
            if (firstOrderId == null) {
                firstOrderId = order.getId();
            }
            for (TicketType ticketType : ticketTypes) {
                orderItemRepository.save(OrderItem.builder()
                        .order(order).ticketType(ticketType)
                        .ticketTypeName(ticketType.getName())
                        .unitPrice(ticketType.getPrice()).quantity(1).build());
            }
        }
    }

    @Test
    @DisplayName("數 SQL：訂單列表與單筆查詢各發幾句")
    void countStatements() throws Exception {
        String token = login();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        mockMvc.perform(get("/api/orders/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(ORDER_COUNT))
                .andExpect(jsonPath("$.content[0].items.length()").value(ITEMS_PER_ORDER));
        long listStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        mockMvc.perform(get("/api/orders/{id}", firstOrderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(ITEMS_PER_ORDER));
        long singleStatements = statistics.getPrepareStatementCount();

        System.out.println("=== SQL 句數 ===");
        System.out.println("GET /api/orders/me  (" + ORDER_COUNT + " 張訂單 × "
                + ITEMS_PER_ORDER + " 筆明細) → " + listStatements);
        System.out.println("GET /api/orders/{id} → " + singleStatements);

        // 句數固定，不隨訂單筆數成長 —— 這就是「沒有 1+N」的定義。
        // 列表的 3 句：
        //   ① users        JwtAuthenticationFilter 每個請求查一次使用者
        //   ② orders       分頁查詢（結果少於一頁且 offset=0，Spring Data 會省略 count 查詢）
        //   ③ order_items  where order_id in (...)，由 Order.orderItems 的 @BatchSize 合併
        // 沒有 ticket_types 查詢 —— 代理的 getId() 不會觸發初始化。
        assertEquals(3, listStatements,
                "訂單列表的 SQL 句數變了，檢查是不是 @BatchSize 失效或多了 1+N");
        // 單筆的 2 句：① users ② orders left join order_items（@EntityGraph）
        assertEquals(2, singleStatements,
                "單筆訂單的 SQL 句數變了，檢查 @EntityGraph 是否還在");
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "%s" }
                                """.formatted(BUYER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
