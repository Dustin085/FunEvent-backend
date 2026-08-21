package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.dto.organizer.OrganizerResponse;
import com.example.funeventbackend.dto.comment.RatingSummary;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventImage;
import com.example.funeventbackend.model.EventStatus;

import java.time.Instant;
import java.util.List;

public record EventResponse(
        Long id,
        OrganizerResponse organizer,
        String name,
        String description,
        Instant startAt,
        Instant endAt,
        /** 常數名（例如 MUSIC_GROOVE），用來組篩選連結與推導圖示檔名 */
        String categoryCode,
        /** 顯示用（例如「音樂律動」） */
        String categoryName,
        /**
         * 常數名（例如 NEW_TAIPEI）。
         * ⚠️ 編輯表單要用它把 select 設回原值 —— 顯示用的簡稱不能拿來當識別碼。
         * 和 categoryCode / categoryName 是同一個模式
         */
        String cityCode,
        /** 已轉成簡稱（例如「新北」），前端不必再維護一份對照表 */
        String city,
        String district,
        String locationName,
        String address,
        /** 依 sort_order 排序，第一張是封面 */
        List<String> imageUrls,
        EventStatus status,
        Instant createdAt,
        /**
         * ⚠️ 沒有任何評論時是 null 而不是 0.0 ——
         * 「沒人評過」和「大家都給 0 分」對使用者是兩件完全不同的事
         */
        Double ratingAverage,
        long ratingCount
) {
    /**
     * ⚠️ 只放在詳情頁，不放在 EventSummaryResponse ——
     * 列表一頁 12 筆，每筆各查一次聚合就是教科書級的 1+N。
     * 卡片上的評分要等到能用一句 GROUP BY 一次撈完再做。
     */
    public static EventResponse from(Event event, RatingSummary rating) {
        return new EventResponse(
                event.getId(),
                OrganizerResponse.from(event.getOrganizer()),
                event.getName(),
                event.getDescription(),
                event.getStartAt(),
                event.getEndAt(),
                event.getCategory().name(),
                event.getCategory().getDisplayName(),
                event.getCity().name(),
                event.getCity().getShortName(),
                event.getDistrict(),
                event.getLocationName(),
                event.getAddress(),
                event.getImages().stream().map(EventImage::getImageUrl).toList(),
                event.getStatus(),
                event.getCreatedAt(),
                rating.average(),
                rating.count()
        );
    }

    /** 建立／更新活動的回應用這個 —— 剛建好的活動不可能有評論 */
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                OrganizerResponse.from(event.getOrganizer()),
                event.getName(),
                event.getDescription(),
                event.getStartAt(),
                event.getEndAt(),
                event.getCategory().name(),
                event.getCategory().getDisplayName(),
                event.getCity().name(),
                event.getCity().getShortName(),
                event.getDistrict(),
                event.getLocationName(),
                event.getAddress(),
                event.getImages().stream().map(EventImage::getImageUrl).toList(),
                event.getStatus(),
                event.getCreatedAt(),
                // 剛建好／剛更新的活動，評分一律以「尚無評價」呈現
                null,
                0
        );
    }
}
