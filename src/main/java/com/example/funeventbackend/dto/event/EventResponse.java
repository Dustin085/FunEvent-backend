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
                event.getLocationName(),
                event.getAddress(),
                event.getStatus(),
                event.getCreatedAt()
        );
    }
}
