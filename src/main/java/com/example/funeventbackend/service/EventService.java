package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.event.*;
import com.example.funeventbackend.dto.ticket.TicketTypeResponse;
import com.example.funeventbackend.exception.InvalidEventDataException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceAccessDeniedException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.Organizer;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.CommentRepository;
import com.example.funeventbackend.repository.EventRepository;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import com.example.funeventbackend.repository.specification.EventSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
    // 同理：只需要評分聚合這個查詢。依賴 CommentService 會造成循環依賴 ——
    // CommentService 需要 EventService 來取「已發布的活動」
    private final CommentRepository commentRepository;
    // 取消活動前要確認沒有人付過款
    private final OrderRepository orderRepository;

    @Transactional
    public EventResponse create(User user, CreateEventRequest dto) {
        validateEventPeriod(dto.startAt(), dto.endAt());
        // 建立一個已經結束的活動沒有意義
        validateNotEnded(dto.endAt());
        // 檢查 User 是否有 Organizer 身分
        Organizer organizer = organizerService.getEntityByUser(user);
        // 建立新 Event Entity
        Event newEvent = Event.builder()
                .organizer(organizer)
                .name(dto.name())
                .description(dto.description())
                .startAt(dto.startAt())
                .endAt(dto.endAt())
                .category(dto.category())
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
    public Page<EventSummaryResponse> search(EventSearchCriteria criteria, Pageable pageable) {
        Instant now = Instant.now();
        // 條件不適用的回傳 null，在這裡濾掉 —— 加新篩選只要多一行，
        // 不會像衍生查詢那樣膨脹成條件的組合數
        List<Specification<Event>> specs = Stream.of(
                        EventSpecifications.isPublished(),
                        EventSpecifications.endsAfter(now),
                        EventSpecifications.hasAnyCategory(criteria.categories()),
                        EventSpecifications.inAnyCity(criteria.cities()),
                        EventSpecifications.keywordMatches(criteria.keyword()))
                .filter(Objects::nonNull)
                .toList();

        return eventRepository.findAll(Specification.allOf(specs), pageable)
                .map(EventSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public EventResponse findById(Long id) {
        // ⚠️ 多一次聚合查詢。詳情頁是「一個活動」所以只會多一句 SQL；
        // 列表頁絕對不能這樣做（12 筆 = 12 句）
        return EventResponse.from(getPublishedEntity(id), commentRepository.findRatingSummary(id));
    }

    /**
     * 主辦者後台的活動列表 —— 含草稿與已取消。
     *
     * <p>⚠️ 和 search() 是**兩種相反的安全預設**：那支是公開的（只回已發布且未結束），
     * 這支只給擁有者（回全部）。刻意分成兩支而不是「同一支依身分決定可見性」——
     * 後者要在每個讀取路徑都記得判斷，漏一個就洩漏草稿。
     */
    @Transactional(readOnly = true)
    public Page<OrganizerEventSummaryResponse> findMine(
            User user, EventStatus status, Pageable pageable) {
        Long organizerId = organizerService.getEntityByUser(user).getId();
        Page<Event> events = status == null
                ? eventRepository.findByOrganizerId(organizerId, pageable)
                : eventRepository.findByOrganizerIdAndStatus(organizerId, status, pageable);
        return events.map(OrganizerEventSummaryResponse::from);
    }

    /**
     * 後台的編輯頁：活動與票種一次拿齊。
     *
     * <p>⚠️ 這裡直接打 ticketTypeRepository 而不是 TicketTypeService.findByEventId ——
     * 那個方法會先呼叫 getPublishedEntity，草稿一律 404（那對公開端點是對的）。
     * 這裡的擁有權已經由 getOwnedEntity 驗過了。
     */
    @Transactional(readOnly = true)
    public OrganizerEventDetailResponse findMineById(User user, Long eventId) {
        Event event = getOwnedEntity(user, eventId);
        List<TicketTypeResponse> ticketTypes =
                ticketTypeRepository.findByEventIdOrderByIdAsc(eventId)
                        .stream()
                        .map(TicketTypeResponse::from)
                        .toList();
        // 用不帶評分的那個 from() —— 編輯活動不需要評分聚合
        return new OrganizerEventDetailResponse(EventResponse.from(event), ticketTypes);
    }

    @Transactional
    public EventResponse cancel(User user, Long eventId) {
        // 悲觀鎖：避免「讀狀態 → 判斷 → 寫狀態」之間被插隊
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(EVENT_NOT_FOUND_MESSAGE));
        assertOwnedBy(user, event);

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new InvalidStateTransitionException("活動已經是取消狀態");
        }
        // ⚠️ 有人付過錢就不能單方面取消 —— 那需要一整套退款流程
        //（退給誰、退多少、綠界的退款 API、部分退款、退款失敗的重試）。
        // 現在明確擋下來，而不是取消完留下一堆「付了錢卻沒有活動」的訂單
        if (orderRepository.existsPaidOrderForEvent(eventId)) {
            throw new InvalidStateTransitionException(
                    "已有付款完成的訂單，請先聯繫客服處理退款");
        }

        // managed entity，髒檢查會在提交時寫入
        event.setStatus(EventStatus.CANCELLED);
        return EventResponse.from(event);
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
        // ⚠️ 只檢查「結束晚於開始」，不檢查「是否已結束」——
        // 主辦者必須能修正已經開始、甚至已經結束的活動（改錯字、補地址）
        validateEventPeriod(dto.startAt(), dto.endAt());
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
        event.setCategory(dto.category());
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
        // ⚠️ 看 endAt 不是 startAt：進行中的展覽／營隊仍然可以上架。
        // 這條規則和列表查詢、購票檢查一致
        if (event.getEndAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException("活動已經結束，無法發布");
        }
        // 修改 status。event 是 managed entity，髒檢查會在提交時寫入，不需要 save()
        event.setStatus(EventStatus.PUBLISHED);
        return EventResponse.from(event);
    }

    /**
     * 結構檢查：結束一定要晚於開始。
     *
     * <p>這是「這組時間本身成不成立」的問題，任何時候都適用 ——
     * 建立、更新都要檢查。
     */
    private void validateEventPeriod(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new InvalidEventDataException("活動結束時間必須晚於開始時間");
        }
    }

    /**
     * 「向前看」的動作專用：不能是已經結束的活動。
     *
     * <p>⚠️ 用 endAt 不是 startAt。用 startAt 的話，一個已經開跑的月長展覽
     * 就永遠無法上架 —— 而我們已經決定「進行中的活動是一等公民」
     *（會被列出、可以購票，見 {@code EventSpecifications.endsAfter}
     * 與 {@code OrderService.validatePurchasable}）。
     *
     * <p>⚠️ update 刻意<b>不</b>呼叫這個：維護既有資料是另一回事，
     * 主辦者必須能修正已經開始、甚至已經結束的活動（改錯字、補地址）。
     * 擋住的話連一個錯字都改不了。
     */
    private void validateNotEnded(Instant endAt) {
        if (endAt.isBefore(Instant.now())) {
            throw new InvalidEventDataException("活動結束時間已過");
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
