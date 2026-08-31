package com.example.funeventbackend.dto.ticket;

import com.example.funeventbackend.model.TicketStatus;

/**
 * 「某票種的某個狀態有幾張票」—— 核銷進度那句 {@code GROUP BY} 的一列。
 *
 * <p>用 JPQL 的建構式查詢直接組成 DTO，而不是回 {@code Object[]} 讓呼叫端自己轉型 ——
 * 跟 {@code EventTicketAggregate}、{@code RatingSummary} 是同一個判斷。
 *
 * <p>⚠️ 這是查詢的中繼結果，不會直接回給前端。對外的形狀是
 * {@link CheckInProgressResponse}。
 */
public record TicketStatusCount(
        Long ticketTypeId,
        TicketStatus status,
        long count
) {
}
