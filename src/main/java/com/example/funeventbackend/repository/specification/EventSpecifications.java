package com.example.funeventbackend.repository.specification;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/**
 * 活動查詢的條件片段。
 *
 * <p>每個方法各自負責一個條件，彼此不知道對方存在；
 * 條件不適用時回傳 {@code null}，由呼叫端過濾掉。
 * 這是為了讓「六個可選篩選條件」不會變成 64 個分支 ——
 * 加一個新條件只要多一個方法和呼叫端的一行。
 *
 * <p>⚠️ 欄位名是字串，打錯只有執行期會知道。EventSearchTest 就是這件事的保護網。
 */
public final class EventSpecifications {
    private EventSpecifications() {
    }

    /** 只列已發布的 */
    public static Specification<Event> isPublished() {
        return (root, query, cb) -> cb.equal(root.get("status"), EventStatus.PUBLISHED);
    }

    /**
     * 只列還沒開始的。
     * 已開始的活動買不到票（validatePurchasable 會擋），列出來只會誤導使用者。
     */
    public static Specification<Event> startsAfter(Instant instant) {
        return (root, query, cb) -> cb.greaterThan(root.get("startAt"), instant);
    }

    public static Specification<Event> hasCategory(Category category) {
        if (category == null) return null;
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Event> inCity(City city) {
        if (city == null) return null;
        return (root, query, cb) -> cb.equal(root.get("city"), city);
    }

    /**
     * 關鍵字：活動名稱或主辦單位名稱。
     *
     * <p>⚠️ 用 LIKE '%q%'，前面有萬用字元就用不到 B-tree 索引 —— 這是全表掃描。
     * 這個選擇是刻意的：PostgreSQL 內建的全文檢索分詞器不認識中文
     *（沒有空格切不開），要能用得裝 zhparser / pg_jieba，而多數託管型
     * PostgreSQL 不給裝。中文沒有詞界，LIKE 反而能正確命中「民謠吉他課」。
     *
     * <p>活動數到「數十萬」等級才需要換方案（pg_trgm 或 Meilisearch 之類）。
     * 在那之前這樣最簡單也最準。
     *
     * <p>沒有搜 description：內文提到一次「親子」不代表那是親子活動，
     * 相關性差，而且 TEXT 欄位的掃描成本高一截。
     */
    public static Specification<Event> keywordMatches(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                // ⚠️ 隱式 JOIN organizers。因為 Event.organizer 是 optional = false，
                // 內部連接不會改變列數，分頁仍然正確
                cb.like(cb.lower(root.get("organizer").get("name")), pattern));
    }
}
