package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.dto.organizer.OrganizerResponse;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;

import java.time.Instant;

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
        /** 已轉成簡稱（例如「新北」），前端不必再維護一份對照表 */
        String city,
        String district,
        String locationName,
        String address,
        EventStatus status,
        Instant createdAt
) {
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
                event.getCity().getShortName(),
                event.getDistrict(),
                event.getLocationName(),
                event.getAddress(),
                event.getStatus(),
                event.getCreatedAt()
        );
    }
}
