package com.example.funeventbackend.dto.order;

import java.math.BigDecimal;

/**
 * 某個活動的銷售摘要。
 *
 * @param eventName       活動名稱。⚠️ 放在這裡是因為端點本來就撈出 Event 了，
 *                        零額外查詢；前端不必為了一個名字多打一支端點
 * @param paidQuantity    已付款張數
 * @param paidAmount      已付款金額。⚠️ 只算這個活動的部分，不是訂單總額
 * @param pendingQuantity 待付款張數 —— 庫存正被佔用，但錢還沒進來。
 *                        這些訂單逾時後會自動取消並回補庫存
 */
public record EventSalesSummary(
        String eventName,
        long paidQuantity,
        BigDecimal paidAmount,
        long pendingQuantity
) {
}
