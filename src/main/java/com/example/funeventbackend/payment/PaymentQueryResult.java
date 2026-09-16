package com.example.funeventbackend.payment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 主動查詢一筆交易得到的結果。
 * <p>
 * 跟 {@link PaymentCallbackResult} 是同一種資料形狀，來源不同——
 * 回呼是金流商推給我們的，這個是我們主動去問的。同樣的規則適用：
 * 這裡的資料只用來「識別」和「比對」，永遠不用來直接「更新」。
 * <p>
 * ⚠️ 比 {@link PaymentCallbackResult} 多一個 {@code paidAt}，不是可有可無的：
 * 回呼是即時的，「現在」拿來判斷訂單過期與否沒有問題；但查詢是<b>事後才發現</b>
 * 已經付款，那筆付款可能是很久以前完成的，拿「現在」去跟訂單的 expiresAt
 * 比對會產生錯誤結論——必須用 ECPay 真正記錄的付款時間，才能正確判斷
 * 「當初付款的那一刻，訂單其實還沒過期」。
 */
public record PaymentQueryResult(
        String merchantTradeNo,
        String gatewayTradeNo,
        boolean paid,
        BigDecimal amount,
        Instant paidAt
) {
}
