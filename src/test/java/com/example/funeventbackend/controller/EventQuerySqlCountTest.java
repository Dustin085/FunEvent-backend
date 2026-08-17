package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.model.EventImage;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首頁活動列表的 SQL 句數。這是全站流量最高的端點，1+N 在這裡最貴。
 * <p>
 * ⚠️ 每個活動都掛在<b>不同的</b> organizer 底下是刻意的：
 * 全部共用同一個 organizer 的話，一級快取只會載入它一次，
 * 就算 @EntityGraph 被拿掉，測試也照樣是綠的 —— 那是假的綠燈。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventQuerySqlCountTest {
    private static final int EVENT_COUNT = 5;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventImageRepository eventImageRepository;
    @Autowired
    private TicketTypeRepository ticketTypeRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

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

        for (int i = 0; i < EVENT_COUNT; i++) {
            User seller = userRepository.save(User.builder()
                    .email("seller" + i + "@example.com").passwordHash("x")
                    .name("賣家 " + i).role(RoleType.USER).build());
            Organizer organizer = organizerRepository.save(Organizer.builder()
                    .user(seller).name("主辦單位 " + i).introduction("簡介 " + i).build());
            Event event = eventRepository.save(Event.builder()
                    .organizer(organizer)
                    .name("測試活動 " + i)
                    .description("SQL 計數用")
                    .startAt(Instant.now().plus(30 + i, ChronoUnit.DAYS))
                    .endAt(Instant.now().plus(31 + i, ChronoUnit.DAYS))
                    .category(Category.LIFE_EXPERIENCE)
                    .city(City.TAIPEI)
                    .district("大安區")
                    .locationName("測試場地 " + i)
                    .status(EventStatus.PUBLISHED)
                    .build());
            // ⚠️ 一定要真的建圖片：沒有圖的話，那句批次查詢即使沒生效也只會是 1 句，
            // 測不出 @BatchSize 有沒有作用（跟「共用同一個 organizer」是同一種假綠燈）
            eventImageRepository.save(EventImage.builder()
                    .event(event)
                    .imageUrl("/images/events/test-" + i + ".jpg")
                    .sortOrder(0)
                    .build());
        }
    }

    @Test
    @DisplayName("數 SQL：首頁活動列表不論幾筆都只發兩句")
    void eventListIssuesTwoStatements() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(EVENT_COUNT))
                .andExpect(jsonPath("$.content[0].organizerName").value("主辦單位 0"))
                // 依 startAt 升冪 → 最早開始的排第一
                .andExpect(jsonPath("$.content[0].name").value("測試活動 0"))
                .andExpect(jsonPath("$.content[0].coverImageUrl").value("/images/events/test-0.jpg"));

        long statements = statistics.getPrepareStatementCount();
        System.out.println("=== GET /api/events (" + EVENT_COUNT + " 筆，每筆 1 張圖) → "
                + statements + " 句 ===");

        // 兩句，而且不隨活動筆數成長：
        //   ① events LEFT JOIN organizers（@EntityGraph 抓「對一」，不影響分頁）
        //   ② event_images where event_id in (...)（@BatchSize 把 N 句併成 1 句）
        // 這個端點是 permitAll，沒帶 token，所以連 JwtAuthenticationFilter 那句 users 查詢都沒有；
        // 結果少於一頁且 offset=0，Spring Data 也省略了 count 查詢。
        assertEquals(2, statements,
                "首頁活動列表的 SQL 句數變了，檢查 @EntityGraph 或 @BatchSize 是不是被拿掉了");
    }
}
