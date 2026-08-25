package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 活動列表用的精簡版本。
 * <p>
 * 不沿用 {@link EventResponse} 的理由是負載：它帶著 {@code description}（TEXT）
 * 和巢狀的 {@code OrganizerResponse.introduction}（也是 TEXT），
 * 一頁 12 筆就是 24 段長文字傳給只顯示卡片的頁面。
 * <p>
 * 列表 DTO 與詳情 DTO 本來就該是不同的東西。
 */
public record EventSummaryResponse(
        Long id,
        String name,
        Instant startAt,
        Instant endAt,
        /** 常數名（例如 MUSIC_GROOVE），用來組篩選連結與推導圖示檔名 */
        String categoryCode,
        /** 顯示用（例如「音樂律動」） */
        String categoryName,
        /** 已轉成簡稱（例如「新北」），前端不必再維護一份對照表 */
        String city,
        String district,
        String locationName,
        /** sort_order 最小的那張。沒有圖時為 null，前端用漸層佔位 */
        String coverImageUrl,
        Long organizerId,
        String organizerName,
        /**
         * 還買得到的票種裡最低的價格。
         * ⚠️ null 代表一張都買不到，<b>不是免費</b> —— 不能在這裡塞 0 帶過
         */
        BigDecimal minPrice,
        /** 所有票種的剩餘張數總和 */
        int remainingStock
) {
    /**
     * ⚠️ 刻意不保留單參數的 from(Event)：全專案只有 EventService.search() 用得到，
     * 留著會讓人不小心走到「沒有價格」的路徑，而卡片一定要顯示價格。
     */
    public static EventSummaryResponse from(Event event, EventTicketAggregate ticketAggregate) {
        return new EventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getStartAt(),
                event.getEndAt(),
                event.getCategory().name(),
                event.getCategory().getDisplayName(),
                event.getCity().getShortName(),
                event.getDistrict(),
                event.getLocationName(),
                event.getImages().isEmpty() ? null : event.getImages().getFirst().getImageUrl(),
                event.getOrganizer().getId(),
                event.getOrganizer().getName(),
                // ⚠️ aggregate 為 null 代表這個活動一張買得到的票都沒有
                //（沒建票種，或全部售完）。文案交給前端決定
                ticketAggregate == null ? null : ticketAggregate.minPrice(),
                ticketAggregate == null ? 0 : ticketAggregate.remainingStock().intValue()
        );
    }
}
