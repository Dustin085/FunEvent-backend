package com.example.funeventbackend.payment;

import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.Payment;

import java.util.Map;
import java.util.Optional;

/**
 * 金流閘道抽象。把「跟哪一家金流商往來」隔離在這層後面，
 * 換金流商時只需要多寫一個實作，Service 與 Controller 完全不用動。
 */
public interface PaymentGateway {

    /** 建立付款，回傳前端需要的資訊。 */
    PaymentInitiation initiate(Order order, Payment payment);

    /**
     * 驗證回呼真偽並解析內容。
     * <p>
     * 回傳 {@code Optional.empty()} 代表<b>驗簽失敗</b> —— 這不是例外而是正常的防禦結果，
     * 用 Optional 逼呼叫端明確處理。回呼端點是 permitAll，全世界都打得到，
     * 它的安全性 100% 建立在這個方法上。
     */
    Optional<PaymentCallbackResult> parseCallback(Map<String, String> params);
}
