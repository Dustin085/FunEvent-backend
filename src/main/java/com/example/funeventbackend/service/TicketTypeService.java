package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.ticket.CreateTicketTypeRequest;
import com.example.funeventbackend.dto.ticket.TicketTypeResponse;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.OrderItemRepository;
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketTypeService {
    private static final String TICKET_TYPE_NOT_FOUND_MESSAGE = "找不到此票種";

    private final TicketTypeRepository ticketTypeRepository;
    private final EventService eventService;
    // 刪除票種前要確認沒有訂單參照它
    private final OrderItemRepository orderItemRepository;

    @Transactional
    public TicketTypeResponse create(User user, Long eventId, CreateTicketTypeRequest dto) {
        // 撈出活動並確認是這位主辦者的
        Event event = eventService.getOwnedEntity(user, eventId);
        // 已取消的活動不該再賣票
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new InvalidStateTransitionException("已取消的活動無法新增票種");
        }
        TicketType newTicketType = TicketType.builder()
                .event(event)
                .name(dto.name())
                .description(dto.description())
                .price(dto.price())
                .capacity(dto.capacity())
                // 初始庫存等於總量，由後端決定，不接受客戶端指定
                .stock(dto.capacity())
                .saleStartAt(dto.saleStartAt())
                .saleEndAt(dto.saleEndAt())
                .build();
        return TicketTypeResponse.from(ticketTypeRepository.save(newTicketType));
    }

    /**
     * 刪除票種。
     *
     * <p>⚠️ 已經有人下過訂單的票種不能刪 —— OrderItem 有 FK 指向它。
     * 訂單裡雖然存了 ticketTypeName / unitPrice 的快照，但關聯本身還在。
     *
     * <p>⚠️ 檢查的是「有沒有任何訂單」而不是「有沒有已付款的訂單」——
     * 待付款的訂單也佔著庫存，刪掉票種等於讓那筆訂單永遠無法完成。
     */
    @Transactional
    public void delete(User user, Long eventId, Long ticketTypeId) {
        // 擁有權檢查：找不到活動或不是你的，這裡就會擋下來
        eventService.getOwnedEntity(user, eventId);

        TicketType ticketType = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(TICKET_TYPE_NOT_FOUND_MESSAGE));

        // ⭐ 必須確認票種真的屬於這個活動。
        // 巢狀路由很容易只驗父資源就放行 —— 那樣的話帶著「自己的 eventId」
        // 加上「別人的 ticketTypeId」就能刪掉別人的票種（IDOR）
        if (!ticketType.getEvent().getId().equals(eventId)) {
            throw new ResourceNotFoundException(TICKET_TYPE_NOT_FOUND_MESSAGE);
        }

        if (orderItemRepository.existsByTicketTypeId(ticketTypeId)) {
            throw new InvalidStateTransitionException("已有訂單購買此票種，無法刪除");
        }

        ticketTypeRepository.delete(ticketType);
    }

    @Transactional(readOnly = true)
    public List<TicketTypeResponse> findByEventId(Long eventId) {
        // 只有已發布的活動才對外公開票種；草稿／已取消一律 404
        eventService.getPublishedEntity(eventId);
        return ticketTypeRepository.findByEventIdOrderByIdAsc(eventId)
                .stream()
                .map(TicketTypeResponse::from)
                .toList();
    }
}
