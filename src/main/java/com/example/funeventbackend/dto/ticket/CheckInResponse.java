package com.example.funeventbackend.dto.ticket;

import com.example.funeventbackend.model.Ticket;

import java.time.Instant;

/**
 * 核銷的結果。
 *
 * <p>⭐ 用結果物件而不是丟例外：對掃票的工作人員來說，「這張票已經用過了」
 * <b>不是錯誤，是需要看到細節的結果</b> —— 什麼時候被用掉的、持票人是誰。
 * 例外只能給一句訊息。
 *
 * <p>⚠️ {@code INVALID} 不帶任何票券資訊 —— 那時根本沒有票可以查。
 * 而且「簽章錯」與「這張票不屬於這個活動」也刻意回同一種結果：
 * 區分了等於告訴對方他猜到了哪一步。
 */
public record CheckInResponse(
        Result result,
        String ticketTypeName,
        /** 持票人（下訂單的人）。⚠️ 工作人員需要核對身分，所以這裡刻意帶出來 */
        String attendeeName,
        /** ALREADY_USED 時是「上次被核銷的時間」。⚠️ 併發搶掃時可能是 null */
        Instant usedAt
) {
    public enum Result {
        SUCCESS,
        ALREADY_USED,
        /** 票已作廢（退款、活動取消）。⚠️ 目前沒有地方會產生這個狀態 */
        VOID,
        /** 簽章不符、格式錯誤，或這張票不屬於這個活動 */
        INVALID
    }

    public static CheckInResponse invalid() {
        return new CheckInResponse(Result.INVALID, null, null, null);
    }

    public static CheckInResponse of(Result result, Ticket ticket) {
        return new CheckInResponse(
                result,
                ticket.getOrderItem().getTicketTypeName(),
                ticket.getOrderItem().getOrder().getUser().getName(),
                ticket.getUsedAt());
    }
}
