package com.example.funeventbackend.service;

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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刪除票種的三條規則。
 *
 * <p>⭐ 第三個測試是重點：巢狀路由很容易只驗父資源就放行，
 * 那樣帶著「自己的 eventId」加上「別人的 ticketTypeId」就能刪掉別人的東西（IDOR）。
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketTypeDeleteTest {

    @Autowired
    private TicketTypeService ticketTypeService;
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
    private Event myEvent;
    private Event myOtherEvent;
    private TicketType myTicket;
    private TicketType ticketOfOtherEvent;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        me = userRepository.save(User.builder()
                .email("me@example.com").passwordHash("$2a$10$dummy")
                .name("我").role(RoleType.USER).build());
        Organizer organizer = organizerRepository.save(Organizer.builder()
                .user(me).name("我的主辦單位").build());

        myEvent = saveEvent(organizer, "活動 A");
        myOtherEvent = saveEvent(organizer, "活動 B");
        myTicket = saveTicket(myEvent, "一般票");
        ticketOfOtherEvent = saveTicket(myOtherEvent, "活動 B 的票");
    }

    private Event saveEvent(Organizer organizer, String name) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("測試用")
                .startAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(11, ChronoUnit.DAYS))
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(EventStatus.DRAFT).build());
    }

    private TicketType saveTicket(Event event, String name) {
        return ticketTypeRepository.save(TicketType.builder()
                .event(event).name(name).price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    @Test
    @DisplayName("沒有訂單的票種可以刪除")
    void deletesTicketTypeWithoutOrders() {
        ticketTypeService.delete(me, myEvent.getId(), myTicket.getId());

        assertTrue(ticketTypeRepository.findById(myTicket.getId()).isEmpty());
    }

    @Test
    @DisplayName("⭐ 已經有訂單購買的票種不能刪 —— 連未付款的也算")
    void cannotDeleteWhenOrdersExist() {
        Order order = orderRepository.save(Order.builder()
                .user(me).totalAmount(new BigDecimal("500.00"))
                // ⚠️ 刻意用 PENDING：待付款的訂單也佔著庫存，
                // 刪掉票種等於讓那筆訂單永遠無法完成
                .status(OrderStatusType.PENDING)
                .expiresAt(Instant.now().plusSeconds(900)).build());
        orderItemRepository.save(OrderItem.builder()
                .order(order).ticketType(myTicket).ticketTypeName(myTicket.getName())
                .unitPrice(myTicket.getPrice()).quantity(1).build());

        assertThrows(InvalidStateTransitionException.class,
                () -> ticketTypeService.delete(me, myEvent.getId(), myTicket.getId()));

        assertTrue(ticketTypeRepository.findById(myTicket.getId()).isPresent());
    }

    @Test
    @DisplayName("⭐ 票種不屬於路徑上的活動時要拒絕（IDOR）")
    void rejectsTicketTypeFromAnotherEvent() {
        // 兩個活動都是我的，擁有權檢查會通過 ——
        // 唯一擋下來的是「票種屬不屬於這個活動」那道檢查
        assertThrows(ResourceNotFoundException.class,
                () -> ticketTypeService.delete(
                        me, myEvent.getId(), ticketOfOtherEvent.getId()));

        assertTrue(ticketTypeRepository.findById(ticketOfOtherEvent.getId()).isPresent());
        assertEquals(2, ticketTypeRepository.count());
    }
}
