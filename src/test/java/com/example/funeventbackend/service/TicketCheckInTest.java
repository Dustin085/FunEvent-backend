package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.ticket.CheckInResponse;
import com.example.funeventbackend.exception.ResourceAccessDeniedException;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderItem;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.Ticket;
import com.example.funeventbackend.model.TicketStatus;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.TicketRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.security.TicketTokenSigner;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 票券的發放與核銷。
 *
 * <p>⭐ 這個檔案守的兩件事都不會在畫面上報錯：
 * <ul>
 *   <li>一張票只能核銷一次 —— 錯了就是同一張票多人入場</li>
 *   <li>只能核銷自己活動的票 —— 錯了就是拿別場的票混進來</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketCheckInTest {

    @Autowired
    private TicketService ticketService;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TicketTokenSigner ticketTokenSigner;
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
    private User otherOrganizerUser;
    private User buyer;
    private Event event;
    private Event otherEvent;
    private Long orderId;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        organizerUser = saveUser("organizer@example.com", "主辦者");
        otherOrganizerUser = saveUser("other@example.com", "另一個主辦者");
        buyer = saveUser("buyer@example.com", "王小明");

        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(organizerUser).name("測試主辦單位").build());
        Organizer otherOrganizer = organizerRepository.save(Organizer.builder()
                .user(otherOrganizerUser).name("別家主辦單位").build());

        event = saveEvent(organizer, "我的活動");
        otherEvent = saveEvent(otherOrganizer, "別人的活動");

        // 買 2 張 → 付款成功後應該展開成 2 筆 Ticket
        orderId = savePaidOrder(buyer, saveTicket(event, "一般票"), 2);
    }

    // ── 發票 ─────────────────────────────────────────────

    @Test
    @DisplayName("⭐ quantity 2 展開成 2 張票，各自獨立")
    void issueExpandsQuantityIntoIndividualTickets() {
        ticketService.issueForOrder(orderId);

        List<Ticket> tickets = ticketRepository.findByOrderItemOrderIdOrderByIdAsc(orderId);
        assertEquals(2, tickets.size(), "一張票一列，兩個人才能分開入場");
        assertEquals(List.of(TicketStatus.VALID, TicketStatus.VALID),
                tickets.stream().map(Ticket::getStatus).toList());
    }

    @Test
    @DisplayName("重複呼叫不會發出第二批")
    void issueIsIdempotent() {
        ticketService.issueForOrder(orderId);
        ticketService.issueForOrder(orderId);

        assertEquals(2, ticketRepository.findByOrderItemOrderIdOrderByIdAsc(orderId).size());
    }

    // ── 核銷 ─────────────────────────────────────────────

    @Test
    @DisplayName("核銷成功：狀態轉成 USED，記下時間與掃描者")
    void checkInMarksTicketUsed() {
        Ticket ticket = firstTicket();

        CheckInResponse response = ticketService.checkIn(
                organizerUser, event.getId(), ticketTokenSigner.sign(ticket.getId()));

        assertEquals(CheckInResponse.Result.SUCCESS, response.result());
        // 工作人員要核對身分，所以回應帶出持票人與票種
        assertEquals("王小明", response.attendeeName());
        assertEquals("一般票", response.ticketTypeName());

        Ticket reloaded = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertEquals(TicketStatus.USED, reloaded.getStatus());
        assertNotNull(reloaded.getUsedAt());
        assertEquals(organizerUser.getId(), reloaded.getCheckedInBy().getId());
    }

    @Test
    @DisplayName("⭐ 同一張票掃第二次：ALREADY_USED，而且不會被重複核銷")
    void secondCheckInIsRejected() {
        Ticket ticket = firstTicket();
        String qr = ticketTokenSigner.sign(ticket.getId());
        ticketService.checkIn(organizerUser, event.getId(), qr);

        CheckInResponse second = ticketService.checkIn(organizerUser, event.getId(), qr);

        // ⚠️ 這條守的是 markUsed 的條件式 UPDATE。
        // 改成「先查狀態再寫」的話，兩個工作人員同時掃會兩個都放行
        assertEquals(CheckInResponse.Result.ALREADY_USED, second.result());
        assertNotNull(second.usedAt(), "要告訴現場的人「什麼時候被用掉的」");
    }

    @Test
    @DisplayName("⭐ 別的活動的票：INVALID，而不是「查到了但拒絕」")
    void ticketFromAnotherEventIsInvalid() {
        Long otherOrderId = savePaidOrder(buyer, saveTicket(otherEvent, "別場的票"), 1);
        ticketService.issueForOrder(otherOrderId);
        Ticket otherTicket = ticketRepository.findByOrderItemOrderIdOrderByIdAsc(otherOrderId).getFirst();

        // 拿別場活動的票，來掃我的活動
        CheckInResponse response = ticketService.checkIn(
                organizerUser, event.getId(), ticketTokenSigner.sign(otherTicket.getId()));

        // ⚠️ 查詢本身就限定了活動 —— 這張票根本撈不出來，
        // 所以不需要「撈出來再檢查活動對不對」那段容易漏寫的程式碼
        assertEquals(CheckInResponse.Result.INVALID, response.result());
        assertEquals(TicketStatus.VALID,
                ticketRepository.findById(otherTicket.getId()).orElseThrow().getStatus(),
                "別人的票不能被我核銷掉");
    }

    @Test
    @DisplayName("簽章被竄改：INVALID")
    void tamperedTokenIsInvalid() {
        Ticket ticket = firstTicket();

        CheckInResponse response = ticketService.checkIn(
                organizerUser, event.getId(), ticket.getId() + ".偽造的簽章");

        assertEquals(CheckInResponse.Result.INVALID, response.result());
    }

    @Test
    @DisplayName("掃到不相干的 QR（網址之類）不會爆掉")
    void unrelatedQrCodeIsInvalid() {
        // 現場掃到 Wi-Fi QR、名片 QR 是常態
        assertEquals(CheckInResponse.Result.INVALID,
                ticketService.checkIn(organizerUser, event.getId(), "https://example.com")
                        .result());
    }

    @Test
    @DisplayName("⭐ 不是自己的活動：403，連掃都不能掃")
    void cannotCheckInForSomeoneElsesEvent() {
        Ticket ticket = firstTicket();

        // ⚠️ 是 403 不是 404 —— 已發布的活動本來就公開，隱瞞存在性沒有意義。
        // 和訂單、草稿活動那種「一律 404」是相反的判斷，因為那些是私有資源
        assertThrows(ResourceAccessDeniedException.class,
                () -> ticketService.checkIn(
                        otherOrganizerUser, event.getId(), ticketTokenSigner.sign(ticket.getId())));
    }

    // ── fixture ──────────────────────────────────────────

    private Ticket firstTicket() {
        ticketService.issueForOrder(orderId);
        return ticketRepository.findByOrderItemOrderIdOrderByIdAsc(orderId).getFirst();
    }

    private User saveUser(String email, String name) {
        return userRepository.save(User.builder()
                .email(email).passwordHash("$2a$10$dummy")
                .name(name).role(RoleType.USER).build());
    }

    private Event saveEvent(Organizer organizer, String name) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().plus(1, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(2, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());
    }

    private TicketType saveTicket(Event event, String name) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name(name).price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    /** 直接建已付款的訂單 —— 這裡要測的是票券，不是下單與付款流程 */
    private Long savePaidOrder(User user, TicketType ticketType, int quantity) {
        Order order = orderRepository.save(Order.builder()
                .user(user)
                .totalAmount(ticketType.getPrice().multiply(BigDecimal.valueOf(quantity)))
                .status(OrderStatusType.PAID)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(ticketType)
                .ticketTypeName(ticketType.getName())
                .unitPrice(ticketType.getPrice()).quantity(quantity).build());
        return order.getId();
    }
}
