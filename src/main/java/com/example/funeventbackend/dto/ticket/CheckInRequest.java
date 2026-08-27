package com.example.funeventbackend.dto.ticket;

import jakarta.validation.constraints.NotBlank;

/**
 * @param token 掃到的 QR 內容，格式是 {@code {ticketId}.{簽章}}。
 *              ⚠️ 這裡只驗「不是空的」—— 格式與簽章的驗證交給 TicketTokenSigner，
 *              而且失敗一律回同一種結果，不區分原因
 */
public record CheckInRequest(
        @NotBlank(message = "請提供票券內容")
        String token
) {
}
