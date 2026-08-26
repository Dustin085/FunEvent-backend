package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.UpdateEventRequest;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.EventImage;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.RoleType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventImageRepository;
import com.example.funeventbackend.repository.OrganizerRepository;
import com.example.funeventbackend.repository.UserRepository;
import com.example.funeventbackend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活動圖片的全量取代與排序。
 *
 * <p>⭐ 為什麼這一段特別需要測試：{@code EventService.replaceImages} 的正確性
 * 依賴一個<b>不明顯的前提</b> —— 它先呼叫 {@code deleteByEventId} 再 {@code saveAll}，
 * 但 Hibernate 的 flush 順序固定是「先 INSERT 再 DELETE」，
 * 也就是實際打到資料庫的順序跟程式碼寫的相反。
 *
 * <p>會正確，是因為衍生的 {@code deleteByEventId} 是<b>逐列按 id 刪除</b>，
 * 碰不到剛插入的新列。⚠️ 哪天有人把它「最佳化」成 {@code @Modifying} 的批次
 * {@code DELETE WHERE event_id = ?}，那句會立即執行，順序就變了 ——
 * 這個檔案就是那個改動的警報器。
 *
 * <p>⚠️ 而且錯了不會報錯：圖片默默少一張、封面默默換人，只有人工比對才看得出來。
 */
@SpringBootTest
@ActiveProfiles("test")
class EventImageTest {

    private static final String PHOTO_A = "https://images.unsplash.com/photo-a";
    private static final String PHOTO_B = "https://images.unsplash.com/photo-b";
    private static final String PHOTO_C = "https://images.unsplash.com/photo-c";

    @Autowired
    private EventService eventService;
    @Autowired
    private EventImageRepository eventImageRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private DatabaseCleaner databaseCleaner;

    private User owner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        owner = userRepository.save(User.builder()
                .email("image-owner@example.com").passwordHash("$2a$10$dummy")
                .name("主辦者").role(RoleType.USER).build());
        organizerRepository.save(Organizer.builder()
                .user(owner).name("測試主辦單位").build());
    }

    @Test
    @DisplayName("建立活動時帶圖：清單的索引就是 sortOrder")
    void createStoresImagesInOrder() {
        EventResponse created = eventService.create(
                owner, createRequest(List.of(PHOTO_A, PHOTO_B, PHOTO_C)));

        // 回應要立刻帶著剛存進去的圖 —— 前端建立完會直接導到編輯頁
        assertEquals(List.of(PHOTO_A, PHOTO_B, PHOTO_C), created.imageUrls());
        assertEquals(List.of(PHOTO_A, PHOTO_B, PHOTO_C), storedUrlsInOrder());
        assertEquals(List.of(0, 1, 2), storedSortOrders());
    }

    @Test
    @DisplayName("建立活動不帶圖：合法，就是沒有圖片")
    void createWithoutImages() {
        EventResponse created = eventService.create(owner, createRequest(List.of()));

        assertTrue(created.imageUrls().isEmpty());
        assertEquals(0, eventImageRepository.count());
    }

    @Test
    @DisplayName("⭐ 更新是全量取代：舊的要消失，不是累加")
    void updateReplacesInsteadOfAppending() {
        EventResponse created = eventService.create(
                owner, createRequest(List.of(PHOTO_A, PHOTO_B)));

        eventService.update(owner, created.id(), updateRequest(List.of(PHOTO_C)));

        // ⚠️ 這條守的正是「先 INSERT 再 DELETE」那個 flush 順序 ——
        // 舊的兩張若沒被刪掉，這裡會看到三張
        assertEquals(List.of(PHOTO_C), storedUrlsInOrder());
        assertEquals(1, eventImageRepository.count());
    }

    @Test
    @DisplayName("⭐ 只換順序：封面要跟著換")
    void updateReorderChangesCover() {
        EventResponse created = eventService.create(
                owner, createRequest(List.of(PHOTO_A, PHOTO_B, PHOTO_C)));

        // 同樣三張，只是把 C 拖到最前面
        EventResponse updated = eventService.update(
                owner, created.id(), updateRequest(List.of(PHOTO_C, PHOTO_A, PHOTO_B)));

        // ⚠️ 順序本身就是資料 —— 第一張是卡片上的封面。
        // 「圖片沒變所以不用動」這種最佳化會讓拖曳排序完全失效
        assertEquals(List.of(PHOTO_C, PHOTO_A, PHOTO_B), updated.imageUrls());
        assertEquals(List.of(PHOTO_C, PHOTO_A, PHOTO_B), storedUrlsInOrder());
        assertEquals(3, eventImageRepository.count(), "只換順序不該多出或少掉圖片");
    }

    @Test
    @DisplayName("更新送空清單：圖片全部清掉")
    void updateWithEmptyListClearsImages() {
        EventResponse created = eventService.create(
                owner, createRequest(List.of(PHOTO_A, PHOTO_B)));

        EventResponse updated = eventService.update(
                owner, created.id(), updateRequest(List.of()));

        assertTrue(updated.imageUrls().isEmpty());
        assertEquals(0, eventImageRepository.count());
    }

    @Test
    @DisplayName("更新送 null 等同空清單")
    void updateWithNullClearsImages() {
        EventResponse created = eventService.create(
                owner, createRequest(List.of(PHOTO_A)));

        eventService.update(owner, created.id(), updateRequest(null));

        assertEquals(0, eventImageRepository.count());
    }

    // ── fixture ──────────────────────────────────────────

    private CreateEventRequest createRequest(List<String> imageUrls) {
        return new CreateEventRequest(
                "有圖的活動", "介紹",
                Instant.now().plus(10, ChronoUnit.DAYS),
                Instant.now().plus(11, ChronoUnit.DAYS),
                Category.MUSIC_GROOVE, City.TAIPEI, "大安區", "場地", "地址",
                imageUrls);
    }

    private UpdateEventRequest updateRequest(List<String> imageUrls) {
        return new UpdateEventRequest(
                "有圖的活動", "介紹",
                Instant.now().plus(10, ChronoUnit.DAYS),
                Instant.now().plus(11, ChronoUnit.DAYS),
                Category.MUSIC_GROOVE, City.TAIPEI, "大安區", "場地", "地址",
                imageUrls);
    }

    /** ⚠️ 一定要自己依 sortOrder 排 —— findAll() 的順序不保證是插入順序 */
    private List<String> storedUrlsInOrder() {
        return eventImageRepository.findAll().stream()
                .sorted(Comparator.comparingInt(EventImage::getSortOrder))
                .map(EventImage::getImageUrl)
                .toList();
    }

    private List<Integer> storedSortOrders() {
        return eventImageRepository.findAll().stream()
                .map(EventImage::getSortOrder)
                .sorted()
                .toList();
    }
}
