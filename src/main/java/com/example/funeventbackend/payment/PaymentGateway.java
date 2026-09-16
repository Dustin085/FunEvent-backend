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

    /**
     * 主動向金流商查詢一筆交易的目前狀態。
     * <p>
     * 用在逾時取消訂單之前，確認有沒有漏接的付款成功回呼——回呼端點理論上
     * 應該保證送達，但網路問題、我方伺服器短暫掛掉都可能讓它永遠不會再重送。
     * <p>
     * 回傳 {@code Optional.empty()} 代表<b>查詢本身失敗</b>（連不上、逾時、
     * 回應解析不了）—— 這時候不能當成「查到了、沒付款」，呼叫端必須保守處理
     * （先不要取消，留給下一輪排程再試）。
     */
    Optional<PaymentQueryResult> queryStatus(String merchantTradeNo);
}
