package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.EventSummaryResponse;
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
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    // 只需要「有沒有票種」這個查詢，依賴 Repository 而非 TicketTypeService，
    // 否則兩個 Service 互相注入會造成循環依賴
    private final TicketTypeRepository ticketTypeRepository;

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
                .city(dto.city())
                .district(dto.district())
                .locationName(dto.locationName())
                .address(dto.address())
                .build();
        // 存入資料庫
        Event savedEvent = eventRepository.save(newEvent);
        return EventResponse.from(savedEvent);
    }

    @Transactional(readOnly = true)
    public Page<EventSummaryResponse> findPublished(Pageable pageable) {
        // 已開始的活動買不到票（validatePurchasable 會擋），列出來只會誤導使用者
        return eventRepository
                .findByStatusAndStartAtAfter(EventStatus.PUBLISHED, Instant.now(), pageable)
                .map(EventSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public EventResponse findById(Long id) {
        return EventResponse.from(getPublishedEntity(id));
    }

    // 給其他 Service 用：只回傳已公開的活動，否則一律 404（不洩漏存在性）
    @Transactional(readOnly = true)
    public Event getPublishedEntity(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE);
        }

        return event;
    }

    // 給其他 Service 用：撈出活動並確認擁有權
    @Transactional(readOnly = true)
    public Event getOwnedEntity(User user, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));
        assertOwnedBy(user, event);
        return event;
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
        event.setCity(dto.city());
        event.setDistrict(dto.district());
        event.setLocationName(dto.locationName());
        event.setAddress(dto.address());
        // 不需要 save()：event 是交易內撈出的 managed entity，
        // 提交時髒檢查會自動發出 UPDATE。create 才需要 save（新物件要靠它拿到 id）
        return EventResponse.from(event);
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
        // 至少要有一個票種
        if (!ticketTypeRepository.existsByEventId(eventId)) {
            throw new InvalidStateTransitionException("活動至少要有一個票種才能發布");
        }
        // 開始時間還沒過
        if (event.getStartAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException("活動開始時間已過，無法發布");
        }
        // 修改 status。event 是 managed entity，髒檢查會在提交時寫入，不需要 save()
        event.setStatus(EventStatus.PUBLISHED);
        return EventResponse.from(event);
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
