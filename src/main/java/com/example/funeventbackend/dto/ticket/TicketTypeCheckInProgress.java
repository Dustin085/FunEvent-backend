package com.example.funeventbackend.dto.ticket;

/**
 * 單一票種的核銷進度。欄位語意與 {@link CheckInProgressResponse} 相同。
 *
 * <p>⚠️ 一張票都沒賣出去的票種<b>也會出現在清單裡</b>（三個數字都是 0）。
 * 這是刻意的：票種從畫面上消失，看起來像是壞掉，而不像是「還沒賣出」。
 */
public record TicketTypeCheckInProgress(
        Long ticketTypeId,
        String name,
        long checkedIn,
        long expected,
        long voided
) {
}
