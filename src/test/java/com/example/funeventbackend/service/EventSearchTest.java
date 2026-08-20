package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.EventSearchCriteria;
import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活動搜尋。這是 Specification 的保護網 ——
 * ⚠️ Criteria API 的欄位名是字串（root.get("category")），打錯只有執行期才會知道，
 * 沒有這些測試的話，改欄位名或打錯字都不會有任何警告。
 */
@SpringBootTest
@ActiveProfiles("test")
class EventSearchTest {
    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startAt", "id"));

    @Autowired
    private EventService eventService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private Organizer lanxiang;
    private Organizer chessClub;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        // ⚠️ organizers.user_id 是 UNIQUE（一個使用者只能有一個主辦身分），
        // 兩個主辦單位必須各自有自己的使用者
        User sellerA = userRepository.save(User.builder()
                .email("seller-a@example.com").passwordHash("$2a$10$dummy")
                .name("賣家 A").role(RoleType.USER).build());
        User sellerB = userRepository.save(User.builder()
                .email("seller-b@example.com").passwordHash("$2a$10$dummy")
                .name("賣家 B").role(RoleType.USER).build());
        lanxiang = organizerRepository.save(Organizer.builder()
                .user(sellerA).name("蘭響音樂教室").build());
        chessClub = organizerRepository.save(Organizer.builder()
                .user(sellerB).name("Chess Club").build());

        saveEvent(lanxiang, "民謠吉他入門", Category.MUSIC_GROOVE, City.TAIPEI,
                EventStatus.PUBLISHED, 10);
        saveEvent(chessClub, "小小棋神夏令營", Category.LIFE_EXPERIENCE, City.NEW_TAIPEI,
                EventStatus.PUBLISHED, 20);
        saveEvent(chessClub, "週末桌遊聚會", Category.LIFE_EXPERIENCE, City.TAIPEI,
                EventStatus.PUBLISHED, 30);
    }

    private Event saveEvent(Organizer organizer, String name, Category category,
                            City city, EventStatus status, int startInDays) {
        return eventRepository.save(Event.builder()
                .organizer(organizer)
                .name(name)
                .description("測試用")
                .startAt(Instant.now().plus(startInDays, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(startInDays + 1, ChronoUnit.DAYS))
                .category(category)
                .city(city)
                .district("測試區")
                .status(status)
                .build());
    }

    private List<String> search(String keyword, Category category, City city) {
        return eventService
                .search(new EventSearchCriteria(keyword, category, city), FIRST_PAGE)
                .map(EventSummaryResponse::name)
                .getContent();
    }

    @Test
    @DisplayName("關鍵字命中活動名稱")
    void keywordMatchesEventName() {
        assertEquals(List.of("民謠吉他入門"), search("吉他", null, null));
    }

    @Test
    @DisplayName("關鍵字命中主辦單位名稱")
    void keywordMatchesOrganizerName() {
        // 「蘭響」不在任何活動名稱裡，只出現在主辦單位上
        assertEquals(List.of("民謠吉他入門"), search("蘭響", null, null));
    }

    @Test
    @DisplayName("關鍵字不分大小寫")
    void keywordIsCaseInsensitive() {
        // 主辦單位是 "Chess Club"，用小寫也要找得到
        List<String> results = search("chess club", null, null);
        assertEquals(2, results.size());
        assertTrue(results.contains("小小棋神夏令營"));
        assertTrue(results.contains("週末桌遊聚會"));
    }

    @Test
    @DisplayName("關鍵字是空白或 null 就不篩選")
    void blankKeywordIsIgnored() {
        assertEquals(3, search(null, null, null).size());
        assertEquals(3, search("", null, null).size());
        assertEquals(3, search("   ", null, null).size());
    }

    @Test
    @DisplayName("city 篩選")
    void filtersByCity() {
        List<String> results = search(null, null, City.TAIPEI);
        assertEquals(2, results.size());
        assertFalse(results.contains("小小棋神夏令營"), "新北的活動不該出現");
    }

    @Test
    @DisplayName("⭐ 三個條件同時給：取交集")
    void combinesAllFilters() {
        // Chess Club 主辦 + 生活體驗 + 台北 → 只有「週末桌遊聚會」
        assertEquals(List.of("週末桌遊聚會"),
                search("chess", Category.LIFE_EXPERIENCE, City.TAIPEI));

        // 同樣的關鍵字與分類，換成新北 → 只有「小小棋神夏令營」
        assertEquals(List.of("小小棋神夏令營"),
                search("chess", Category.LIFE_EXPERIENCE, City.NEW_TAIPEI));
    }

    @Test
    @DisplayName("⚠️ 未發布與已開始的活動不會出現在任何搜尋結果裡")
    void neverReturnsDraftOrStartedEvents() {
        saveEvent(lanxiang, "還沒發布的活動", Category.MUSIC_GROOVE, City.TAIPEI,
                EventStatus.DRAFT, 15);
        // startInDays 為負 = 已經開始了
        saveEvent(lanxiang, "已經開始的活動", Category.MUSIC_GROOVE, City.TAIPEI,
                EventStatus.PUBLISHED, -1);

        // 不管用什麼條件組合都不該撈到
        assertFalse(search(null, null, null).contains("還沒發布的活動"));
        assertFalse(search(null, null, null).contains("已經開始的活動"));
        assertTrue(search("還沒發布", null, null).isEmpty());
        assertTrue(search("已經開始", null, null).isEmpty());
        assertTrue(search(null, Category.MUSIC_GROOVE, City.TAIPEI).size() == 1,
                "音樂類台北只該剩下那場吉他課");
    }
}
