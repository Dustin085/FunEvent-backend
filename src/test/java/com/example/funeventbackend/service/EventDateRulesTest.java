package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.UpdateEventRequest;
import com.example.funeventbackend.exception.InvalidEventDataException;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 活動的時間規則。
 *
 * <p>⭐ 分界線是「向前看 vs 維護」：
 * <ul>
 *   <li>create / publish 是向前看的動作 → 不能是已經結束的活動</li>
 *   <li>update 是維護 → 只擋語意錯誤（結束早於開始）</li>
 * </ul>
 *
 * <p>⚠️ 三處全部看 endAt 而不是 startAt，和列表查詢
 *（EventSpecifications.endsAfter）與購票檢查一致 ——
 * 「進行中的活動是一等公民」這個決定必須在每個地方都成立，
 * 只改一半會做出「看得到買不到」「上架不了」「改不了錯字」這些互相矛盾的行為。
 */
@SpringBootTest
@ActiveProfiles("test")
class EventDateRulesTest {

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
    private DatabaseCleaner databaseCleaner;

    private User owner;
    private Organizer organizer;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        owner = userRepository.save(User.builder()
                .email("owner@example.com").passwordHash("$2a$10$dummy")
                .name("主辦者").role(RoleType.USER).build());
        organizer = organizerRepository.save(Organizer.builder()
                .user(owner).name("測試主辦單位").build());
    }

    private Event saveEvent(String name, EventStatus status,
                            Instant startAt, Instant endAt) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name(name).description("原本的介紹")
                .startAt(startAt).endAt(endAt)
                .category(Category.MUSIC_GROOVE).city(City.TAIPEI).district("大安區")
                .status(status).build());
    }

    private void saveTicket(Event event) {
        ticketTypeRepository.save(TicketType.builder()
                .event(event).name("一般票").price(new BigDecimal("500.00"))
                .capacity(10).stock(10).build());
    }

    private UpdateEventRequest updateRequest(String name, Instant startAt, Instant endAt) {
        // 這個檔案測的是日期規則，圖片一律空清單
        return new UpdateEventRequest(name, "修正後的介紹", startAt, endAt,
                Category.MUSIC_GROOVE, City.TAIPEI, "大安區", "場地", "地址", List.of());
    }

    @Test
    @DisplayName("⭐ 已經開始的活動可以編輯 —— 不然連一個錯字都改不了")
    void canUpdateOngoingEvent() {
        Instant startAt = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant endAt = Instant.now().plus(30, ChronoUnit.DAYS);
        Event ongoing = saveEvent("打錯字的活動", EventStatus.PUBLISHED, startAt, endAt);

        EventResponse updated = eventService.update(owner, ongoing.getId(),
                updateRequest("改好的活動", startAt, endAt));

        assertEquals("改好的活動", updated.name());
    }

    @Test
    @DisplayName("⭐ 已經結束的活動也可以編輯（補地址、修正紀錄）")
    void canUpdateEndedEvent() {
        Instant startAt = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant endAt = Instant.now().minus(9, ChronoUnit.DAYS);
        Event ended = saveEvent("結束的活動", EventStatus.PUBLISHED, startAt, endAt);

        EventResponse updated = eventService.update(owner, ended.getId(),
                updateRequest("結束的活動（已補地址）", startAt, endAt));

        assertEquals("結束的活動（已補地址）", updated.name());
    }

    @Test
    @DisplayName("結束早於開始一律拒絕 —— 這是語意錯誤，編輯也不能放行")
    void rejectsInvertedPeriodOnUpdate() {
        Instant startAt = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant endAt = Instant.now().plus(11, ChronoUnit.DAYS);
        Event event = saveEvent("正常的活動", EventStatus.DRAFT, startAt, endAt);

        assertThrows(InvalidEventDataException.class,
                () -> eventService.update(owner, event.getId(),
                        updateRequest("壞掉的時間", endAt, startAt)));
    }

    @Test
    @DisplayName("⭐ 進行中的活動可以發布（月長展覽晚一步上架的情況）")
    void canPublishOngoingEvent() {
        Event ongoing = saveEvent("進行中的展覽", EventStatus.DRAFT,
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS));
        saveTicket(ongoing);

        EventResponse published = eventService.publish(owner, ongoing.getId());

        assertEquals(EventStatus.PUBLISHED, published.status());
    }

    @Test
    @DisplayName("已經結束的活動不能發布")
    void cannotPublishEndedEvent() {
        Event ended = saveEvent("結束的活動", EventStatus.DRAFT,
                Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(9, ChronoUnit.DAYS));
        saveTicket(ended);

        assertThrows(RuntimeException.class,
                () -> eventService.publish(owner, ended.getId()));
    }

    @Test
    @DisplayName("不能建立一個已經結束的活動")
    void cannotCreateEndedEvent() {
        CreateEventRequest request = new CreateEventRequest(
                "早就結束的活動", "介紹",
                Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(9, ChronoUnit.DAYS),
                Category.MUSIC_GROOVE, City.TAIPEI, "大安區", "場地", "地址", List.of());

        assertThrows(InvalidEventDataException.class,
                () -> eventService.create(owner, request));
    }

    @Test
    @DisplayName("⭐ 但可以建立一個「已經開始、還沒結束」的活動")
    void canCreateOngoingEvent() {
        CreateEventRequest request = new CreateEventRequest(
                "進行中的展覽", "介紹",
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now().plus(30, ChronoUnit.DAYS),
                Category.MUSIC_GROOVE, City.TAIPEI, "大安區", "場地", "地址", List.of());

        EventResponse created = eventService.create(owner, request);

        assertEquals("進行中的展覽", created.name());
        assertEquals(EventStatus.DRAFT, created.status());
    }
}
