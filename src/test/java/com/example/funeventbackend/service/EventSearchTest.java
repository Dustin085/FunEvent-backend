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
import java.util.ArrayList;
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
        return searchMulti(keyword,
                category == null ? null : List.of(category),
                city == null ? null : List.of(city));
    }

    private List<String> searchMulti(String keyword, List<Category> categories, List<City> cities) {
        return eventService
                .search(new EventSearchCriteria(keyword, categories, cities), FIRST_PAGE)
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
    @DisplayName("⭐ 多選分類：同一欄位內是 OR")
    void multipleCategoriesAreOred() {
        // 兩個分類都勾 → 三場活動全中
        assertEquals(3, searchMulti(null,
                List.of(Category.MUSIC_GROOVE, Category.LIFE_EXPERIENCE), null).size());

        // 只勾音樂 → 只剩吉他課
        assertEquals(List.of("民謠吉他入門"),
                searchMulti(null, List.of(Category.MUSIC_GROOVE), null));
    }

    @Test
    @DisplayName("⭐ 多選地區：同一欄位內是 OR")
    void multipleCitiesAreOred() {
        assertEquals(3, searchMulti(null, null,
                List.of(City.TAIPEI, City.NEW_TAIPEI)).size());
    }

    @Test
    @DisplayName("⭐ 不同欄位之間是 AND：多選分類 × 多選地區取交集")
    void differentFacetsAreAnded() {
        // (音樂 OR 生活體驗) AND (新北 OR 高雄) → 只有新北那場
        assertEquals(List.of("小小棋神夏令營"), searchMulti(null,
                List.of(Category.MUSIC_GROOVE, Category.LIFE_EXPERIENCE),
                List.of(City.NEW_TAIPEI, City.KAOHSIUNG)));
    }

    @Test
    @DisplayName("⚠️ List 裡的 null 要被濾掉，不能變成 IN (null)")
    void nullElementsAreIgnored() {
        // ?category= 這種空字串會讓 Spring 放一個 null 進 List。
        // 沒濾掉的話 IN (null) 在 SQL 裡永遠不成立 —— 會靜默地變成查不到任何東西
        List<Category> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals(3, searchMulti(null, withNull, null).size(),
                "整串都是 null 應該等同於沒有篩選");

        List<Category> mixed = new ArrayList<>();
        mixed.add(Category.MUSIC_GROOVE);
        mixed.add(null);
        assertEquals(List.of("民謠吉他入門"), searchMulti(null, mixed, null));
    }

    @Test
    @DisplayName("空的 List 等同於沒有篩選")
    void emptyListIsIgnored() {
        assertEquals(3, searchMulti(null, List.of(), List.of()).size());
    }

    @Test
    @DisplayName("⚠️ 未發布與已結束的活動不會出現在任何搜尋結果裡")
    void neverReturnsDraftOrEndedEvents() {
        saveEvent(lanxiang, "還沒發布的活動", Category.MUSIC_GROOVE, City.TAIPEI,
                EventStatus.DRAFT, 15);
        // startInDays = -5 → endAt 是 -4 天，明確已經結束。
        // ⚠️ 不能用 -1：endAt 會落在「現在」附近，測試會隨執行時機時綠時紅
        saveEvent(lanxiang, "已經結束的活動", Category.MUSIC_GROOVE, City.TAIPEI,
                EventStatus.PUBLISHED, -5);

        // 不管用什麼條件組合都不該撈到
        assertFalse(search(null, null, null).contains("還沒發布的活動"));
        assertFalse(search(null, null, null).contains("已經結束的活動"));
        assertTrue(search("還沒發布", null, null).isEmpty());
        assertTrue(search("已經結束", null, null).isEmpty());
        assertEquals(1, search(null, Category.MUSIC_GROOVE, City.TAIPEI).size(),
                "音樂類台北只該剩下那場吉他課");
    }

    @Test
    @DisplayName("⭐ 進行中的活動（已開始、還沒結束）仍然要出現在列表上")
    void includesOngoingEvents() {
        // 昨天開始、下個月才結束 —— 展覽、營隊、長期課程都是這種形狀。
        // 用 startAt 篩的話這種活動開始第二天就從整個網站消失了。
        //
        // ⚠️ 不能用 saveEvent(..., -1)：那個 helper 的 endAt 固定是 startAt + 1 天，
        // 算出來剛好等於「現在」，測試會隨執行時機時綠時紅
        eventRepository.save(Event.builder()
                .organizer(lanxiang)
                .name("進行中的展覽")
                .description("測試用")
                .startAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .category(Category.ART_CULTURE)
                .city(City.TAIPEI)
                .district("測試區")
                .status(EventStatus.PUBLISHED)
                .build());

        assertTrue(search(null, null, null).contains("進行中的展覽"));
        assertTrue(search("進行中", null, null).contains("進行中的展覽"));
    }
}
