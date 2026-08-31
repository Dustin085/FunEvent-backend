package com.example.funeventbackend.dto.ticket;

import java.util.List;

/**
 * 主辦者的核銷進度：「已入場 120 / 應到 300」，並且拆到票種。
 *
 * <p>⭐ {@code expected} 是「應到人數」＝ VALID + USED，<b>刻意排除 VOID</b>。
 * 退掉的票不該算進分母 —— 現場的人要看的是「還有多少人沒進來」，
 * 而不是「總共賣過幾張」。{@code TicketStatus.VOID} 目前還沒有任何地方會設定
 *（退票功能未做），但先這樣定義，之後做退票時這個端點的語意不用改。
 *
 * <p>{@code voided} 仍然單獨回傳，因為「有幾張退掉了」本身是主辦者想知道的事，
 * 只是不該混進分母。
 *
 * @param checkedIn    已入場（USED）
 * @param expected     應到人數（VALID + USED）
 * @param voided       已作廢（VOID）
 * @param byTicketType 拆到票種的同一組數字，順序與活動的票種順序一致
 */
public record CheckInProgressResponse(
        long checkedIn,
        long expected,
        long voided,
        List<TicketTypeCheckInProgress> byTicketType
) {
}
