package com.example.funeventbackend.payment;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.Payment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 開發與測試用的假金流閘道。不驗簽、不連外。
 * <p>
 * ⚠️ 它<b>不會</b>主動打你的 callback —— 真實世界裡那是金流商的伺服器從外部發起的。
 * 開發時要自己用 Postman POST 到 /api/payments/callback 來模擬。
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "fake", matchIfMissing = true)
@Slf4j
public class FakePaymentGateway implements PaymentGateway {

    @PostConstruct
    void warnThatThisIsNotSafeForProduction() {
        log.warn("⚠️ 目前使用假金流閘道：/api/payments/callback 不驗簽，"
                + "任何人都能把訂單標記為已付款。正式環境必須設定 app.payment.gateway");
    }

    @Override
    public PaymentInitiation initiate(Order order, Payment payment) {
        // .invalid 是保留的頂級網域，永遠不會解析成功 —— 刻意讓人一眼看出這不是真的付款頁
        return new PaymentInitiation(
                "https://fake-gateway.invalid/pay/" + payment.getMerchantTradeNo(),
                Map.of());
    }

    @Override
    public Optional<PaymentCallbackResult> parseCallback(Map<String, String> params) {
        // 真實閘道在這裡的第一件事是驗簽，失敗就回 Optional.empty()。
        // 假閘道沒有簽章，只檢查必要欄位齊全。
        String merchantTradeNo = params.get("merchantTradeNo");
        String amount = params.get("amount");
        if (merchantTradeNo == null || amount == null) {
            return Optional.empty();
        }
        return Optional.of(new PaymentCallbackResult(
                merchantTradeNo,
                params.getOrDefault("gatewayTradeNo", "FAKE-" + merchantTradeNo),
                "1".equals(params.get("success")),
                new BigDecimal(amount)));
    }
}
