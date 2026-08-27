package com.example.funeventbackend.dto.ticket;

import com.example.funeventbackend.model.Ticket;
import com.example.funeventbackend.model.TicketStatus;

import java.time.Instant;

/**
 * 「我的票券」用。
 *
 * <p>⚠️ {@code qrContent} 是<b>可入場的憑證</b>（簽章後的字串），
 * 只會出現在訂單擁有者自己的請求裡。顯示它的頁面必須 noindex。
 *
 * <p>⚠️ 已使用的票也照樣帶 qrContent —— 讓畫面能把它淡化顯示。
 * 那串字送出去也用不了：核銷是條件式 UPDATE，第二次一定回 0。
 */
public record TicketResponse(
        Long id,
        String eventName,
        String ticketTypeName,
        TicketStatus status,
        /** 已核銷的時間。⚠️ 還沒使用的票是 null */
        Instant usedAt,
        String qrContent
) {
    public static TicketResponse from(Ticket ticket, String qrContent) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getOrderItem().getTicketType().getEvent().getName(),
                ticket.getOrderItem().getTicketTypeName(),
                ticket.getStatus(),
                ticket.getUsedAt(),
                qrContent);
    }
}
