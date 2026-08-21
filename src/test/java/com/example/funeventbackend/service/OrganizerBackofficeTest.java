package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.OrganizerEventDetailResponse;
import com.example.funeventbackend.dto.event.OrganizerEventSummaryResponse;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主辦者後台的讀取路徑。
 *
 * <p>⭐ 這些端點和公開的 /api/events 是**相反的安全預設**：公開端點只回
 * 已發布且未結束的，這裡回全部（含草稿）。兩組測試守的是同一條界線的兩側 ——
 * 「我看得到自己的草稿」和「我看不到別人的草稿」。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrganizerBackofficeTest {
    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

    @Autowired
    private EventService eventService;
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

    private User me;
    private User someoneElse;
    private Organizer myOrganizer;
    private Event myDraft;
    private Event myPublished;
    private Event theirDraft;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        me = userRepository.save(User.builder()
                .email("me@example.com").passwordHash("$2a$10$dummy")
                .name("我").role(RoleType.USER).build());
        someoneElse = userRepository.save(User.builder()
                .email("other@example.com").passwordHash("$2a$10$dummy")
                .name("別人").role(RoleType.USER).build());

        myOrganizer = organizerRepository.save(Organizer.builder()
                .user(me).name("我的主辦單位").build());
        Organizer theirOrganizer = organizerRepository.save(Organizer.builder()
                .user(someoneElse).name("別人的主辦單位").build());

        myDraft = saveEvent(myOrganizer, "我的草稿", EventStatus.DRAFT);
        myPublished = saveEvent(myOrganizer, "我已發布的活動", EventStatus.PUBLISHED);
        theirDraft = saveEvent(theirOrganizer, "別人的草稿", EventStatus.DRAFT);
    }

    private Event saveEvent(Organizer organizer, String name, EventStatus status) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(11, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(status).build());
    }

    private TicketType saveTicket(Event event, String name) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name(name).price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    private List<String> myEventNames(EventStatus status) {
        return eventService.findMine(me, status, FIRST_PAGE)
                .map(OrganizerEventSummaryResponse::name)
                .getContent();
    }

    @Test
    @DisplayName("⭐ 後台列表看得到自己的草稿（公開列表看不到）")
    void listsOwnDrafts() {
        List<String> names = myEventNames(null);

        assertEquals(2, names.size());
        assertTrue(names.contains("我的草稿"));
        assertTrue(names.contains("我已發布的活動"));
    }

    @Test
    @DisplayName("⭐ 後台列表不會出現別人的活動")
    void neverListsOtherOrganizersEvents() {
        assertTrue(myEventNames(null).stream().noneMatch(n -> n.startsWith("別人")));
    }

    @Test
    @DisplayName("可以只看某個狀態")
    void filtersByStatus() {
        assertEquals(List.of("我的草稿"), myEventNames(EventStatus.DRAFT));
        assertEquals(List.of("我已發布的活動"), myEventNames(EventStatus.PUBLISHED));
    }

    @Test
    @DisplayName("⭐ 草稿的票種：後台拿得到（公開端點會 404）")
    void loadsTicketTypesOfDraft() {
        saveTicket(myDraft, "早鳥票");
        saveTicket(myDraft, "一般票");

        OrganizerEventDetailResponse detail = eventService.findMineById(me, myDraft.getId());

        assertEquals("我的草稿", detail.event().name());
        assertEquals(EventStatus.DRAFT, detail.event().status());
        assertEquals(List.of("早鳥票", "一般票"),
                detail.ticketTypes().stream().map(t -> t.name()).toList());
    }

    @Test
    @DisplayName("⭐ 讀別人的草稿：一律 404（連存在性都不洩漏）")
    void cannotReadOthersDraft() {
        assertThrows(ResourceNotFoundException.class,
                () -> eventService.findMineById(me, theirDraft.getId()));
    }

    @Test
    @DisplayName("取消活動：狀態變成 CANCELLED")
    void cancelsEvent() {
        eventService.cancel(me, myPublished.getId());

        assertEquals(EventStatus.CANCELLED,
                eventRepository.findById(myPublished.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("⭐ 已經有人付款完成時不能取消 —— 那需要一整套退款流程")
    void cannotCancelWhenSomeonePaid() {
        TicketType ticket = saveTicket(myPublished, "一般票");
        Order order = orderRepository.save(Order.builder()
                .user(someoneElse).totalAmount(new BigDecimal("500.00"))
                .status(OrderStatusType.PAID)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(ticket).ticketTypeName(ticket.getName())
                .unitPrice(ticket.getPrice()).quantity(1).build());

        assertThrows(InvalidStateTransitionException.class,
                () -> eventService.cancel(me, myPublished.getId()));

        // 狀態必須原封不動 —— 擋下來的操作不該留下副作用
        assertEquals(EventStatus.PUBLISHED,
                eventRepository.findById(myPublished.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("未付款的訂單不擋取消")
    void pendingOrderDoesNotBlockCancel() {
        TicketType ticket = saveTicket(myPublished, "一般票");
        Order order = orderRepository.save(Order.builder()
                .user(someoneElse).totalAmount(new BigDecimal("500.00"))
                .status(OrderStatusType.PENDING)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(ticket).ticketTypeName(ticket.getName())
                .unitPrice(ticket.getPrice()).quantity(1).build());

        eventService.cancel(me, myPublished.getId());

        assertEquals(EventStatus.CANCELLED,
                eventRepository.findById(myPublished.getId()).orElseThrow().getStatus());
    }
}
