package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventImage;
import com.example.funeventbackend.model.EventStatus;

import java.time.Instant;
import java.util.Comparator;

/**
 * 主辦者後台的活動列表。
 *
 * <p>⚠️ 不沿用 {@link EventSummaryResponse}：那是給公開卡片的，
 * 帶著 organizerId / organizerName（後台看自己的活動不需要），
 * 卻少了後台最需要的 status。
 * 消費者不同 → DTO 不同，和 EventResponse vs EventSummaryResponse 是同一個判斷。
 *
 * <p>⚠️ 刻意沒有「票種數」—— 那是另一個聚合，一頁 20 筆就是 20 句 SQL。
 * 要加的話得用 {@code IN (...) GROUP BY} 批次查，那和活動卡的
 * minPrice / remainingStock 是同一個題目，該一起做。
 */
public record OrganizerEventSummaryResponse(
        Long id,
        String name,
        EventStatus status,
        Instant startAt,
        Instant endAt,
        String categoryName,
        /** sort_order 最小的那張。沒有圖時為 null */
        String coverImageUrl,
        Instant createdAt
) {
    public static OrganizerEventSummaryResponse from(Event event) {
        return new OrganizerEventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getStatus(),
                event.getStartAt(),
                event.getEndAt(),
                event.getCategory().getDisplayName(),
                event.getImages().stream()
                        .min(Comparator.comparingInt(EventImage::getSortOrder))
                        .map(EventImage::getImageUrl)
                        .orElse(null),
                event.getCreatedAt()
        );
    }
}
