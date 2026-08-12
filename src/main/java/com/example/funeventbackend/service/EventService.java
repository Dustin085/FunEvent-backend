package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.UpdateEventRequest;
import com.example.funeventbackend.exception.InvalidEventDataException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceAccessDeniedException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class EventService {
    private static final String EVENT_NOT_FOUND_MESSAGE = "找不到此活動";
    private static final String EVENT_ACCESS_DENIED_MESSAGE = "沒有讀寫此活動的權限";
    private final EventRepository eventRepository;
    private final OrganizerService organizerService;

    @Transactional
    public EventResponse create(User user, CreateEventRequest dto) {
        validateEventDates(dto.startAt(), dto.endAt());
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

    @Transactional(readOnly = true)
    public EventResponse findById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse update(User user, Long eventId, UpdateEventRequest dto) {
        // 驗證活動時間是否有效
        validateEventDates(dto.startAt(), dto.endAt());
        // 撈出 Event + 權限檢查
        // 悲觀鎖撈出 Event，鎖住直到交易結束
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));
        // 權限檢查
        assertOwnedBy(user, event);
        // 修改 Event
        event.setName(dto.name());
        event.setDescription(dto.description());
        event.setStartAt(dto.startAt());
        event.setEndAt(dto.endAt());
        event.setLocationName(dto.locationName());
        event.setAddress(dto.address());
        // 存入資料庫
        Event savedEvent = eventRepository.save(event);
        return EventResponse.from(savedEvent);
    }

    @Transactional
    public EventResponse publish(User user, Long eventId) {
        // 悲觀鎖撈出 Event，鎖住直到交易結束
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));
        // 權限檢查
        assertOwnedBy(user, event);

        // 確認目前是可發布狀態
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new InvalidStateTransitionException("活動不在可發布狀態");
        }
        // TODO: 等 TicketTypeRepository 建好後補上「至少要有一個票種」
        // 開始時間還沒過
        if (event.getStartAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException("活動開始時間已過，無法發布");
        }
        // 修改 status
        event.setStatus(EventStatus.PUBLISHED);
        // 存 DB
        Event savedEvent = eventRepository.save(event);
        return EventResponse.from(savedEvent);
    }

    private void validateEventDates(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new InvalidEventDataException("活動結束時間必須晚於開始時間");
        }
        if (startAt.isBefore(Instant.now())) {
            throw new InvalidEventDataException("活動開始時間不能早於現在");
        }
    }

    private void assertOwnedBy(User user, Event event) {
        // 檢查使用者是否有權限修改 這個 Event
        Organizer userOrganizer = organizerService.getEntityByUser(user);
        Organizer eventOrganizer = event.getOrganizer();
        if (!userOrganizer.getId().equals(eventOrganizer.getId())) {
            // 非公開活動丟找不到
            if (event.getStatus() != EventStatus.PUBLISHED) {
                throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
            }
            // 公開活動丟權限不足
            throw new ResourceAccessDeniedException(EVENT_ACCESS_DENIED_MESSAGE);
        }
    }
}
