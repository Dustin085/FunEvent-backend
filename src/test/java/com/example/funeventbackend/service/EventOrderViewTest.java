package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.order.EventOrderItemResponse;
import com.example.funeventbackend.dto.order.EventSalesSummary;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 主辦者的訂單檢視。
 *
 * <p>⭐ 核心是第二個測試：一筆訂單可以跨活動下單，
 * 主辦者只該看到「自己活動的那幾行明細」。
 * 用 Order 當查詢單位的話，totalAmount 與其他 items 會把
 * 別的主辦者的銷售資料一起送出去。
 */
@SpringBootTest
@ActiveProfiles("test")
class EventOrderViewTest {
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    private OrderService orderService;
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
    private User buyer;
    private Event myEvent;
    private Event theirEvent;
    private TicketType myTicket;
    private TicketType theirTicket;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        me = userRepository.save(user("me@example.com", "我"));
        User someoneElse = userRepository.save(user("other@example.com", "別的主辦者"));
        buyer = userRepository.save(user("buyer@example.com", "陳小姐"));

        Organizer mine = organizerRepository.save(Organizer.builder()
                .user(me).name("我的主辦單位").build());
        Organizer theirs = organizerRepository.save(Organizer.builder()
                .user(someoneElse).name("別人的主辦單位").build());

        myEvent = saveEvent(mine, "我的活動");
        theirEvent = saveEvent(theirs, "別人的活動");
        myTicket = saveTicket(myEvent, "我的一般票", "500.00");
        theirTicket = saveTicket(theirEvent, "別人的 VIP 票", "1300.00");
    }

    private User user(String email, String name) {
        return User.builder().email(email).passwordHash("$2a$10$dummy")
                .name(name).role(RoleType.USER).build();
    }

    private Event saveEvent(Organizer organizer, String name) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(11, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.PUBLISHED).build());
    }

    private TicketType saveTicket(Event event, String name, String price) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name(name).price(new BigDecimal(price))
                .capacity(50).stock(50).build());
    }

    /** 建一筆訂單，可以一次包含多個活動的票種 */
    private Order saveOrder(OrderStatusType status, TicketType ticket, int quantity) {
        return saveOrder(status, List.of(new Line(ticket, quantity)));
    }

    private record Line(TicketType ticketType, int quantity) {
    }

    private Order saveOrder(OrderStatusType status, List<Line> lines) {
        BigDecimal total = lines.stream()
                .map(l -> l.ticketType().getPrice()
                        .multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Order order = orderRepository.save(Order.builder()
                .user(buyer).totalAmount(total).status(status)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        for (Line line : lines) {
            orderItemRepository.save(OrderItem.builder()
                    .order(order).ticketType(line.ticketType())
                    .ticketTypeName(line.ticketType().getName())
                    .unitPrice(line.ticketType().getPrice())
                    .quantity(line.quantity()).build());
        }
        return order;
    }

    private List<EventOrderItemResponse> myOrders(OrderStatusType status) {
        return orderService.findEventOrders(me, myEvent.getId(), status, FIRST_PAGE)
                .getContent();
    }

    @Test
    @DisplayName("看得到自己活動的銷售明細，含買家姓名")
    void listsOwnEventOrders() {
        saveOrder(OrderStatusType.PAID, myTicket, 2);

        List<EventOrderItemResponse> items = myOrders(null);

        assertEquals(1, items.size());
        assertEquals("陳小姐", items.get(0).buyerName());
        assertEquals("我的一般票", items.get(0).ticketTypeName());
        assertEquals(2, items.get(0).quantity());
        assertEquals(0, new BigDecimal("1000.00").compareTo(items.get(0).subtotal()));
    }

    @Test
    @DisplayName("⭐ 跨活動的訂單：只回自己活動的那一行明細")
    void onlyReturnsLinesOfOwnEvent() {
        // 同一筆訂單，一半是我的票、一半是別人的
        saveOrder(OrderStatusType.PAID,
                List.of(new Line(myTicket, 2), new Line(theirTicket, 1)));

        List<EventOrderItemResponse> items = myOrders(null);

        assertEquals(1, items.size(), "不該看到別人活動的明細");
        assertEquals("我的一般票", items.get(0).ticketTypeName());
        // ⚠️ 小計是「這一行」的金額（500 × 2），不是訂單總額（1000 + 1300）
        assertEquals(0, new BigDecimal("1000.00").compareTo(items.get(0).subtotal()));
    }

    @Test
    @DisplayName("⭐ 看不到別人活動的訂單")
    void cannotViewOthersEventOrders() {
        saveOrder(OrderStatusType.PAID, theirTicket, 1);

        // ⚠️ 這裡是 403 不是 404：那場活動已發布，存在性本來就是公開的，
        // 回 404 只是在假裝它不存在。私有資源（例如別人的訂單）才用 404 ——
        // 那時 403 會證實 id 存在，可以被用來探測。
        assertThrows(ResourceAccessDeniedException.class,
                () -> orderService.findEventOrders(
                        me, theirEvent.getId(), null, FIRST_PAGE));
        assertThrows(ResourceAccessDeniedException.class,
                () -> orderService.getEventSalesSummary(me, theirEvent.getId()));
    }

    @Test
    @DisplayName("⭐ 別人的草稿活動：404，連存在性都不洩漏")
    void cannotViewOthersDraftEventOrders() {
        Event theirDraft = eventRepository.save(Event.builder()
                .organizer(theirEvent.getOrganizer())
                .name("別人的草稿").description("測試用")
                .startAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(11, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.DRAFT).build());

        assertThrows(ResourceNotFoundException.class,
                () -> orderService.findEventOrders(
                        me, theirDraft.getId(), null, FIRST_PAGE));
    }

    @Test
    @DisplayName("可以只看某個狀態")
    void filtersByStatus() {
        saveOrder(OrderStatusType.PAID, myTicket, 2);
        saveOrder(OrderStatusType.PENDING, myTicket, 1);

        assertEquals(2, myOrders(null).size());
        assertEquals(1, myOrders(OrderStatusType.PAID).size());
        assertEquals(1, myOrders(OrderStatusType.PENDING).size());
    }

    @Test
    @DisplayName("銷售摘要：已付款與待付款分開算，取消的不列入")
    void summarisesSales() {
        saveOrder(OrderStatusType.PAID, myTicket, 2);
        saveOrder(OrderStatusType.PAID, myTicket, 3);
        saveOrder(OrderStatusType.PENDING, myTicket, 1);
        // 已取消的票已經還回庫存，不該算進銷售
        saveOrder(OrderStatusType.CANCELLED, myTicket, 10);
        // 別人活動的訂單也不該混進來
        saveOrder(OrderStatusType.PAID, theirTicket, 5);

        EventSalesSummary summary = orderService.getEventSalesSummary(me, myEvent.getId());

        assertEquals(5, summary.paidQuantity());
        assertEquals(0, new BigDecimal("2500.00").compareTo(summary.paidAmount()));
        assertEquals(1, summary.pendingQuantity());
    }

    @Test
    @DisplayName("⚠️ 完全沒有訂單時摘要是 0，不是 null")
    void summaryIsZeroWhenNoOrders() {
        EventSalesSummary summary = orderService.getEventSalesSummary(me, myEvent.getId());

        assertEquals(0, summary.paidQuantity());
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.paidAmount()));
        assertEquals(0, summary.pendingQuantity());
    }
}
