package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.ticket.CreateTicketTypeRequest;
import com.example.funeventbackend.dto.ticket.TicketTypeResponse;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.model.Event;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.TicketType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketTypeService {
    private final TicketTypeRepository ticketTypeRepository;
    private final EventService eventService;

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
