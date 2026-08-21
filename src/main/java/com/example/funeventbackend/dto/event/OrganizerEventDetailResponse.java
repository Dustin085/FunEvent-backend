package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.dto.ticket.TicketTypeResponse;

import java.util.List;

/**
 * 編輯頁一次拿齊活動與票種 —— 不必打兩支端點。
 *
 * <p>⚠️ 公開的 GET /api/events/{id}/ticket-types 對草稿會回 404
 *（那是刻意的：未發布的活動不該洩漏票種），所以後台需要自己這一支。
 */
public record OrganizerEventDetailResponse(
        EventResponse event,
        List<TicketTypeResponse> ticketTypes
) {
}
