package com.example.funeventbackend.payment;

import java.math.BigDecimal;

/**
 * 回呼解析出來的結果。
 * <p>
 * ⚠️ 這裡的資料只用來「識別」和「比對」，永遠不用來直接「更新」。
 * 金額要跟我方記錄的 {@code Payment.amount} 比對，不是拿來覆寫。
 */
public record PaymentCallbackResult(
        String merchantTradeNo,
        String gatewayTradeNo,
        boolean success,
        BigDecimal amount
) {
}
