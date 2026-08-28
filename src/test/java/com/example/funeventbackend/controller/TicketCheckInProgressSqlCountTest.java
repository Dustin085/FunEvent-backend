package com.example.funeventbackend.controller;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
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
import com.example.funeventbackend.service.TicketService;
import com.example.funeventbackend.support.DatabaseCleaner;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 核銷進度的 SQL 句數。
 *
 * <p>⚠️ 這裡真正要守的是「句數不隨票種數成長」——
 * 主辦者若把票種拆得很細（早鳥／一般／VIP／學生／團體…），
 * 「每個票種各查一次」會安靜地變成 N 句，而畫面完全看不出差別。
 *
 * <p>⚠️ 票種數刻意設成 {@value #TICKET_TYPE_COUNT} 而不是 1：
 * 只有一個票種時，逐票種查詢跟一句 GROUP BY 的句數一模一樣，
 * 測試會是假的綠燈。跟 {@code EventQuerySqlCountTest} 用「每個活動不同 organizer」
 * 防假綠燈是同一種手法。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketCheckInProgressSqlCountTest {
    private static final int TICKET_TYPE_COUNT = 5;

    @Autowired
    private TicketService ticketService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
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
    private DatabaseCleaner databaseCleaner;

    private User organizerUser;
    private Event event;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        organizerUser = userRepository.save(User.builder()
                .email("organizer@example.com").passwordHash("x")
                .name("主辦者").role(RoleType.USER).build());
        User buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("x")
                .name("買家").role(RoleType.USER).build());

        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(organizerUser).name("測試主辦單位").build());

        event = eventRepository.save(Event.builder()
                .organizer(organizer).name("多票種活動").description("SQL 計數用")
                .startAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(2, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());

        for (int i = 0; i < TICKET_TYPE_COUNT; i++) {
            TicketType ticketType = ticketTypeRepository.save(TicketType.builder()
                    .event(event).name("票種 " + i).price(new BigDecimal("500.00"))
                    .capacity(10).stock(10).build());

            Order order = orderRepository.save(Order.builder()
                    .user(buyer).totalAmount(new BigDecimal("1000.00"))
                    .status(OrderStatusType.PAID)
                    .expiresAt(Instant.now().plusSeconds(900)).build());
            orderItemRepository.save(OrderItem.builder()
                    .order(order).ticketType(ticketType)
                    .ticketTypeName(ticketType.getName())
                    .unitPrice(ticketType.getPrice()).quantity(2).build());
            ticketService.issueForOrder(order.getId());
        }
    }

    @Test
    @DisplayName("數 SQL：核銷進度不論幾個票種都只發四句")
    void progressIssuesFourStatements() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var progress = ticketService.progress(organizerUser, event.getId());

        assertEquals(TICKET_TYPE_COUNT, progress.byTicketType().size());
        assertEquals(TICKET_TYPE_COUNT * 2, progress.expected());

        long statements = statistics.getPrepareStatementCount();
        System.out.println("=== 核銷進度（" + TICKET_TYPE_COUNT + " 個票種）→ "
                + statements + " 句 ===");

        // 四句，而且不隨票種數成長：
        //   ① events（getOwnedEntity 撈活動）
        //   ② organizers（權限檢查要比對擁有者）
        //   ③ ticket_types where event_id = ?（票種清單，讓沒賣出的也列得出來）
        //   ④ tickets 的 COUNT GROUP BY ticket_type_id, status（一句算完所有數字）
        assertEquals(4, statements,
                "核銷進度的 SQL 句數變了 —— 檢查是不是被改成「每個票種各查一次」");
    }
}
