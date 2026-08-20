package com.example.funeventbackend.repository.specification;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
    /** 單一篩選欄位最多接受幾個值。防止有人送一萬個參數做出超大的 IN 子句 */
    private static final int MAX_FILTER_VALUES = 30;

    private EventSpecifications() {
    }

    /** 只列已發布的 */
    public static Specification<Event> isPublished() {
        return (root, query, cb) -> cb.equal(root.get("status"), EventStatus.PUBLISHED);
    }

    /**
     * 只列還沒結束的。進行中的活動仍然會出現在列表上。
     *
     * <p>⚠️ 用 endAt 而不是 startAt 是刻意的。用 startAt 的話：
     * <ul>
     *   <li>為期一個月的展覽，開始第二天就從整個網站上消失了</li>
     *   <li>「可以評論」要求活動已開始，於是<b>有評論的活動永遠不會出現在列表上</b>——
     *       卡片上的評分做出來會永遠是「尚無評價」</li>
     * </ul>
     *
     * <p>「開演即停售」這種需求由票種的 saleEndAt 表達 —— 那是主辦者自己決定的，
     * 不該由系統一刀切成「所有活動都當單場演唱會」。
     * OrderService.validatePurchasable 的規則與這裡一致（也是看 endAt）。
     */
    public static Specification<Event> endsAfter(Instant instant) {
        return (root, query, cb) -> cb.greaterThan(root.get("endAt"), instant);
    }

    /**
     * 分類多選。同一組之內是 OR：勾了「音樂律動」和「運動休閒」＝兩者皆可。
     *
     * <p>⚠️ 這裡只能是 OR。一個活動只有一個分類，
     * {@code category = 音樂 AND category = 運動} 永遠是空集合。
     *
     * <p>不同組之間（分類 vs 地區）才是 AND —— 那是由呼叫端把多個
     * Specification 用 allOf 串起來達成的，不在這個方法裡。
     *
     * <p>⚠️ tags 之後不能照抄這個：一個活動可以有多個標籤，
     * 「親子 AND 室內」是有意義的查詢，語意要另外設計。
     */
    public static Specification<Event> hasAnyCategory(List<Category> categories) {
        List<Category> values = sanitize(categories);
        if (values.isEmpty()) return null;
        return (root, query, cb) -> root.get("category").in(values);
    }

    /** 地區多選，語意同 {@link #hasAnyCategory} */
    public static Specification<Event> inAnyCity(List<City> cities) {
        List<City> values = sanitize(cities);
        if (values.isEmpty()) return null;
        return (root, query, cb) -> root.get("city").in(values);
    }

    /**
     * 清掉 null 並限制數量。
     *
     * <p>⚠️ null 會出現是因為 {@code ?category=}（空字串）——
     * Spring 轉不出 enum 時會放一個 null 進 List，而 {@code IN (null)}
     * 在 SQL 裡永遠不成立，會讓整個篩選靜默地變成「查不到任何東西」。
     *
     * <p>⚠️ 上限是防濫用：API 是公開的，有人可以送一萬個 ?city= 做出
     * 一個超大的 IN 子句。超過就截斷而不是報錯 —— 正常使用者不可能超過。
     */
    private static <T> List<T> sanitize(List<T> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_FILTER_VALUES)
                .toList();
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
