package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Event;

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
        String locationName,
        Long organizerId,
        String organizerName
) {
    public static EventSummaryResponse from(Event event) {
        return new EventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getStartAt(),
                event.getEndAt(),
                event.getLocationName(),
                event.getOrganizer().getId(),
                event.getOrganizer().getName()
        );
    }
}
