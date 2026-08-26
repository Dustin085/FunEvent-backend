package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.comment.CreateCommentRequest;
import com.example.funeventbackend.dto.comment.MyCommentResponse;
import com.example.funeventbackend.exception.ResourceAccessDeniedException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 會員中心的評論功能：查自己對某活動的評論、我的評論列表、刪除。
 *
 * <p>⭐ 這三支的共同重點是**只能碰到自己的東西**。
 * 資格規則（買過票、活動已開始）在 {@link CommentServiceTest}，這裡不重複。
 */
@SpringBootTest
@ActiveProfiles("test")
class MyCommentsTest {

    @Autowired
    private CommentService commentService;
    @Autowired
    private CommentRepository commentRepository;
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

    private User buyer;
    private User another;
    private Event concert;
    private Event workshop;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        User seller = saveUser("seller@example.com", "賣家");
        buyer = saveUser("buyer@example.com", "買家");
        another = saveUser("another@example.com", "另一位買家");
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(seller).name("測試主辦").build());

        // 兩個都已經開始，兩個人都買過票 —— 讓每個案例自己決定要評哪一個
        concert = saveEvent(organizer, "演唱會");
        workshop = saveEvent(organizer, "工作坊");
        for (Event event : List.of(concert, workshop)) {
            TicketType ticket = saveTicket(event);
            givePaidOrder(buyer, ticket);
            givePaidOrder(another, ticket);
        }
    }

    // ── 查自己對某活動的評論 ──────────────────────────────

    @Test
    @DisplayName("評過就查得到")
    void findMyCommentReturnsOwnComment() {
        commentService.create(buyer, concert.getId(), new CreateCommentRequest(5, "很棒"));

        var found = commentService.findMyComment(buyer, concert.getId());

        assertEquals(5, found.rating());
        assertEquals("很棒", found.content());
    }

    @Test
    @DisplayName("沒評過回 404 —— 前端靠這個決定要不要顯示評論表單")
    void findMyCommentThrowsWhenNotCommented() {
        assertThrows(ResourceNotFoundException.class,
                () -> commentService.findMyComment(buyer, concert.getId()));
    }

    @Test
    @DisplayName("⭐ 別人評過不算我評過")
    void findMyCommentIgnoresOtherPeoplesComments() {
        commentService.create(another, concert.getId(), new CreateCommentRequest(5, "別人的"));

        // 查不到才對 —— 回傳別人那則的話，畫面會顯示成「你已經評論過了」，
        // 使用者從此再也寫不了自己的評論
        assertThrows(ResourceNotFoundException.class,
                () -> commentService.findMyComment(buyer, concert.getId()));
    }

    // ── 我的評論列表 ─────────────────────────────────────

    @Test
    @DisplayName("⭐ 只列出自己的評論，而且帶得回活動名稱與 id")
    void findMineReturnsOnlyOwnCommentsWithEventInfo() {
        commentService.create(buyer, concert.getId(), new CreateCommentRequest(5, "我的演唱會評論"));
        commentService.create(buyer, workshop.getId(), new CreateCommentRequest(3, "我的工作坊評論"));
        commentService.create(another, concert.getId(), new CreateCommentRequest(1, "別人的"));

        Page<MyCommentResponse> mine = commentService.findMine(buyer, newestFirst());

        assertEquals(2, mine.getTotalElements(), "只能有自己的兩則");
        // eventName / eventId 是這個 DTO 存在的理由 —— 列表要能點回活動
        assertEquals(List.of("我的工作坊評論", "我的演唱會評論"),
                mine.getContent().stream().map(MyCommentResponse::content).toList());
        assertEquals(List.of(workshop.getName(), concert.getName()),
                mine.getContent().stream().map(MyCommentResponse::eventName).toList());
        assertEquals(workshop.getId(), mine.getContent().getFirst().eventId());
    }

    /**
     * 和 {@code UserCommentController} 的 {@code @PageableDefault} 同一組排序。
     *
     * <p>⚠️ 排序的**預設值**掛在 controller 上，service 自己不保證順序 ——
     * 所以這裡必須明確帶上，不能只寫 {@code PageRequest.of(0, 10)}。
     *
     * <p>⭐ 而 id 這個第二鍵不是裝飾：同一個測試裡連續建立的兩則評論，
     * createdAt 很可能落在同一毫秒。只靠 createdAt 排序時資料庫回傳的順序
     * 不保證穩定，這個測試就會變成偶爾失敗的那種 ——
     * 正式環境的症狀則是分頁時同一則出現在兩頁、或兩頁都漏掉。
     */
    private PageRequest newestFirst() {
        return PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    // ── 刪除 ─────────────────────────────────────────────

    @Test
    @DisplayName("刪掉自己的評論")
    void deleteOwnComment() {
        var comment = commentService.create(buyer, concert.getId(),
                new CreateCommentRequest(5, "待會要刪掉"));

        commentService.delete(buyer, comment.id());

        assertEquals(0, commentRepository.count());
    }

    @Test
    @DisplayName("⭐ 刪不掉別人的評論，而且那則要還在")
    void cannotDeleteOthersComment() {
        var othersComment = commentService.create(another, concert.getId(),
                new CreateCommentRequest(5, "別人的"));

        assertThrows(ResourceAccessDeniedException.class,
                () -> commentService.delete(buyer, othersComment.id()));
        assertEquals(1, commentRepository.count(), "別人的評論不能被刪掉");
    }

    @Test
    @DisplayName("刪不存在的 id 回 404")
    void deleteMissingCommentThrows() {
        assertThrows(ResourceNotFoundException.class,
                () -> commentService.delete(buyer, 999_999L));
    }

    @Test
    @DisplayName("⭐ 刪掉之後可以重新評論 —— 這就是『改評論』的替代路徑")
    void canCommentAgainAfterDelete() {
        var first = commentService.create(buyer, concert.getId(),
                new CreateCommentRequest(1, "手滑點成一星"));
        commentService.delete(buyer, first.id());

        // ⚠️ 這條守的是「硬刪不軟刪」：軟刪的話 UNIQUE(event_id, user_id)
        // 還在，這裡會拋 AlreadyCommentedException ——
        // 使用者刪了評論卻永遠寫不了新的，比不能刪還糟
        var second = commentService.create(buyer, concert.getId(),
                new CreateCommentRequest(5, "其實很好"));

        assertEquals(5, second.rating());
        assertEquals(1, commentRepository.count());
    }

    // ── fixture ──────────────────────────────────────────

    private User saveUser(String email, String name) {
        return userRepository.save(User.builder()
                .email(email).passwordHash("$2a$10$dummy")
                .name(name).role(RoleType.USER).build());
    }

    /** 都是「兩天前開始」的活動，所以一律可以評論 */
    private Event saveEvent(Organizer organizer, String name) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .endAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());
    }

    private TicketType saveTicket(Event event) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name("一般票").price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    /** 直接建訂單而不走 OrderService —— 這裡要測的不是下單流程 */
    private void givePaidOrder(User user, TicketType ticketType) {
        Order order = orderRepository.save(Order.builder()
                .user(user).totalAmount(new BigDecimal("500.00"))
                .status(OrderStatusType.PAID)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(ticketType)
                .ticketTypeName(ticketType.getName())
                .unitPrice(ticketType.getPrice()).quantity(1).build());
    }
}
