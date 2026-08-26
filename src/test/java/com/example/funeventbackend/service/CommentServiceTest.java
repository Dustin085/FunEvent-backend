package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.comment.CommentEligibilityResponse;
import com.example.funeventbackend.dto.comment.CreateCommentRequest;
import com.example.funeventbackend.dto.comment.RatingSummary;
import com.example.funeventbackend.exception.AlreadyCommentedException;
import com.example.funeventbackend.exception.CommentNotAllowedException;
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
import com.example.funeventbackend.repository.CommentRepository;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 評論的資格規則與評分聚合。
 *
 * <p>⭐ 「只有買過票的參加者能評論」是評分有意義的前提 ——
 * 任何人都能評的話，分數只反映誰有空刷，不再反映參加者的體驗。
 * 這個檔案裡有一半的測試在守這條規則。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommentServiceTest {

    @Autowired
    private CommentService commentService;
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
    private CommentRepository commentRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private User buyer;
    private User stranger;
    private Event startedEvent;
    private Event futureEvent;
    private TicketType startedTicket;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        User seller = userRepository.save(User.builder()
                .email("seller@example.com").passwordHash("$2a$10$dummy")
                .name("賣家").role(RoleType.USER).build());
        buyer = userRepository.save(User.builder()
                .email("buyer@example.com").passwordHash("$2a$10$dummy")
                .name("買家").role(RoleType.USER).build());
        stranger = userRepository.save(User.builder()
                .email("stranger@example.com").passwordHash("$2a$10$dummy")
                .name("路人").role(RoleType.USER).build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller).name("測試主辦").build());

        // 已經開始的活動（可以評論）
        startedEvent = saveEvent(organizer, "已開始的活動", -2);
        startedTicket = saveTicket(startedEvent);
        // 還沒開始的活動（不能評論）
        futureEvent = saveEvent(organizer, "還沒開始的活動", 10);
        saveTicket(futureEvent);
    }

    private Event saveEvent(Organizer organizer, String name, int startInDays) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().plus(startInDays, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(startInDays + 1, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());
    }

    private TicketType saveTicket(Event event) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name("一般票").price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    /** 直接建訂單而不走 OrderService —— 這裡要測的是評論資格，不是下單流程 */
    private void giveOrder(User user, TicketType ticketType, OrderStatusType status) {
        Order order = orderRepository.save(Order.builder()
                .user(user).totalAmount(new BigDecimal("500.00")).status(status)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(ticketType)
                .ticketTypeName(ticketType.getName())
                .unitPrice(ticketType.getPrice()).quantity(1).build());
    }

    private CreateCommentRequest request(int rating) {
        return new CreateCommentRequest(rating, "測試評論內容");
    }

    @Test
    @DisplayName("買過票且活動已開始：可以評論")
    void paidAttendeeCanComment() {
        giveOrder(buyer, startedTicket, OrderStatusType.PAID);

        var response = commentService.create(buyer, startedEvent.getId(), request(5));

        assertEquals(5, response.rating());
        assertEquals("買家", response.userName());
        assertEquals(1, commentRepository.count());
    }

    @Test
    @DisplayName("⭐ 沒買過票的人不能評論")
    void strangerCannotComment() {
        assertThrows(CommentNotAllowedException.class,
                () -> commentService.create(stranger, startedEvent.getId(), request(1)));
        assertEquals(0, commentRepository.count());
    }

    @Test
    @DisplayName("⭐ 訂單還沒付款不算數")
    void pendingOrderIsNotEnough() {
        giveOrder(buyer, startedTicket, OrderStatusType.PENDING);

        assertThrows(CommentNotAllowedException.class,
                () -> commentService.create(buyer, startedEvent.getId(), request(5)));
        assertEquals(0, commentRepository.count());
    }

    @Test
    @DisplayName("活動還沒開始不能評論")
    void cannotCommentBeforeEventStarts() {
        // 這個人確實買了「還沒開始的活動」的票，但活動還沒發生
        TicketType futureTicket = ticketTypeRepository
                .findAll().stream()
                .filter(t -> t.getEvent().getId().equals(futureEvent.getId()))
                .findFirst().orElseThrow();
        giveOrder(buyer, futureTicket, OrderStatusType.PAID);

        assertThrows(CommentNotAllowedException.class,
                () -> commentService.create(buyer, futureEvent.getId(), request(5)));
    }

    @Test
    @DisplayName("⭐ 同一個人對同一個活動只能評一次")
    void cannotCommentTwice() {
        giveOrder(buyer, startedTicket, OrderStatusType.PAID);
        commentService.create(buyer, startedEvent.getId(), request(5));

        assertThrows(AlreadyCommentedException.class,
                () -> commentService.create(buyer, startedEvent.getId(), request(1)));
        assertEquals(1, commentRepository.count());
    }

    @Test
    @DisplayName("評分聚合：5 / 4 / 3 三則 → 平均 4.0、共 3 則")
    void aggregatesRating() {
        User another = userRepository.save(User.builder()
                .email("another@example.com").passwordHash("$2a$10$dummy")
                .name("另一位買家").role(RoleType.USER).build());
        User third = userRepository.save(User.builder()
                .email("third@example.com").passwordHash("$2a$10$dummy")
                .name("第三位買家").role(RoleType.USER).build());
        giveOrder(buyer, startedTicket, OrderStatusType.PAID);
        giveOrder(another, startedTicket, OrderStatusType.PAID);
        giveOrder(third, startedTicket, OrderStatusType.PAID);

        commentService.create(buyer, startedEvent.getId(), request(5));
        commentService.create(another, startedEvent.getId(), request(4));
        commentService.create(third, startedEvent.getId(), request(3));

        RatingSummary summary = commentService.getRatingSummary(startedEvent.getId());
        assertEquals(4.0, summary.average(), 0.0001);
        assertEquals(3, summary.count());
    }

    // ── 資格查詢 ─────────────────────────────────────────
    // ⭐ 這一組跟 create 共用 findBlockingReason，所以它們同時是
    // 「eligibility 回對答案」和「create 的規則沒被改壞」的保護網

    @Test
    @DisplayName("買過票且活動已開始：可以評論")
    void eligibilityAllowsPaidAttendee() {
        giveOrder(buyer, startedTicket, OrderStatusType.PAID);

        var eligibility = commentService.checkEligibility(buyer, startedEvent.getId());

        assertTrue(eligibility.canComment());
        assertNull(eligibility.reason());
    }

    @Test
    @DisplayName("⭐ 沒買票的人：回 NOT_ATTENDED，而不是讓他看到表單")
    void eligibilityRejectsStranger() {
        var eligibility = commentService.checkEligibility(stranger, startedEvent.getId());

        // ⚠️ 這條是這支端點存在的理由：沒有它，前端會顯示一張
        // 填完按下送出才被 403 打回票的表單
        assertFalse(eligibility.canComment());
        assertEquals(CommentEligibilityResponse.Reason.NOT_ATTENDED, eligibility.reason());
    }

    @Test
    @DisplayName("訂單還沒付款也算沒買票")
    void eligibilityRejectsPendingOrder() {
        giveOrder(buyer, startedTicket, OrderStatusType.PENDING);

        var eligibility = commentService.checkEligibility(buyer, startedEvent.getId());

        assertEquals(CommentEligibilityResponse.Reason.NOT_ATTENDED, eligibility.reason());
    }

    @Test
    @DisplayName("活動還沒開始：回 NOT_STARTED")
    void eligibilityRejectsBeforeStart() {
        TicketType futureTicket = ticketTypeRepository.findAll().stream()
                .filter(t -> t.getEvent().getId().equals(futureEvent.getId()))
                .findFirst().orElseThrow();
        giveOrder(buyer, futureTicket, OrderStatusType.PAID);

        var eligibility = commentService.checkEligibility(buyer, futureEvent.getId());

        // ⚠️ 順序有意義：買了票但活動還沒開始，要回 NOT_STARTED 不是 NOT_ATTENDED
        assertEquals(CommentEligibilityResponse.Reason.NOT_STARTED, eligibility.reason());
    }

    @Test
    @DisplayName("已經評過：回 ALREADY_COMMENTED")
    void eligibilityRejectsAfterCommenting() {
        giveOrder(buyer, startedTicket, OrderStatusType.PAID);
        commentService.create(buyer, startedEvent.getId(), request(5));

        var eligibility = commentService.checkEligibility(buyer, startedEvent.getId());

        assertEquals(CommentEligibilityResponse.Reason.ALREADY_COMMENTED, eligibility.reason());
    }

    @Test
    @DisplayName("⚠️ 沒有任何評論時平均分是 null，不是 0.0")
    void ratingIsNullWhenNoComments() {
        RatingSummary summary = commentService.getRatingSummary(startedEvent.getId());

        // 「沒人評過」和「大家都給 0 分」對使用者是兩件完全不同的事。
        // 回 0.0 的話畫面上會顯示「0.0 分」，等於憑空給了一個最差評價
        assertNull(summary.average());
        assertEquals(0, summary.count());
    }
}
