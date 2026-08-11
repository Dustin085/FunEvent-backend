package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.exception.InvalidEventDataException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {
    private final EventRepository eventRepository;
    private final OrganizerService organizerService;

    @Transactional
    public EventResponse create(User user, CreateEventRequest dto) {
        if (!dto.endAt().isAfter(dto.startAt())) {
            throw new InvalidEventDataException("活動結束時間必須晚於開始時間");
        }
        // 檢查 User 是否有 Organizer 身分
        Organizer organizer = organizerService.getEntityByUser(user);
        // 建立新 Event Entity
        Event newEvent = Event.builder()
                .organizer(organizer)
                .name(dto.name())
                .description(dto.description())
                .startAt(dto.startAt())
                .endAt(dto.endAt())
                .locationName(dto.locationName())
                .address(dto.address())
                .build();
        // 存入資料庫
        Event savedEvent = eventRepository.save(newEvent);
        return EventResponse.from(savedEvent);
    }
}
