package com.example.funeventbackend.dto.event;

import java.math.BigDecimal;

/**
 * 一個活動的票種聚合結果（最低價與剩餘張數）。
 *
 * <p>用 JPQL 的建構式查詢直接組成 DTO，而不是回 Object[] 讓呼叫端自己轉型 ——
 * 和 {@code RatingSummary} 是同一個判斷。
 *
 * <p>⚠️ remainingStock 是 Long 不是 Integer —— JPQL 的 SUM 對整數欄位回傳 Long。
 */
public record EventTicketAggregate(
        Long eventId,
        BigDecimal minPrice,
        Long remainingStock
) {
}
