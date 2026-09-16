package com.example.funeventbackend.service;

import com.example.funeventbackend.exception.InvalidPaymentCallbackException;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.PaymentStatusType;
import com.example.funeventbackend.payment.PaymentCallbackOutcome;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 付款結果的共用判斷邏輯——不管結果是從回呼收到的，還是主動查詢問到的，
 * 都走這裡，避免同一條規則在兩個地方各寫一份、悄悄長歪。
 *
 * <p>獨立成自己的 class（不是 {@code PaymentService} 的私有方法），是因為
 * {@code PaymentReconciliationService}（取消前主動查詢綠界）也要呼叫它，
 * 而它必須是<b>跨 bean 呼叫</b>才會經過 Spring 的 AOP 代理、讓 {@code @Transactional}
 * 生效。如果放在 {@code PaymentService} 裡，{@code PaymentService.initiate()}
 * 要觸發它時就會變成同一個 class 內部呼叫，代理失效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentResultApplier {
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TicketService ticketService;

    /**
     * @param rawCallback 只有回呼路徑有這份原始資料可以存證；主動查詢沒有「回呼」
     *                    這個概念，傳 {@code null} 即可。
     * @param paidAt      付款完成的那一刻，用來跟 {@code Order.expiresAt} 比對。
     *                    回呼路徑直接用「現在」；查詢路徑必須用金流商記錄的真正付款
     *                    時間——那筆付款可能是很久以前完成的，拿「現在」去判斷
     *                    會把一筆明明準時付款、只是回呼漏接的交易誤判成逾期。
     */
    @Transactional
    public PaymentCallbackOutcome apply(
            String merchantTradeNo, boolean success, BigDecimal amount,
            String gatewayTradeNo, Instant paidAt, String rawCallback) {
        // 悲觀鎖：鎖住之後「讀狀態 → 判斷 → 寫」在 Java 裡才是安全的
        Payment payment = paymentRepository.findByMerchantTradeNoForUpdate(merchantTradeNo)
                .orElseThrow(() -> new InvalidPaymentCallbackException("找不到對應的付款記錄"));

        // ⭐ 冪等：重複的結果在這裡就返回，不再改任何資料，而且回應仍然是成功。
        // 金流商收不到成功回應會不斷重送，第二次回錯誤只會讓它一直重試。
        if (payment.getStatus() != PaymentStatusType.PENDING) {
            log.info("重複的付款結果，已忽略 merchantTradeNo={}", merchantTradeNo);
            return PaymentCallbackOutcome.DUPLICATE;
        }

        if (rawCallback != null) {
            payment.setRawCallback(rawCallback);
        }

        if (!success) {
            payment.setStatus(PaymentStatusType.FAILED);
            return PaymentCallbackOutcome.PAYMENT_FAILED;
        }

        // 金額只拿來比對，不拿來更新。不符就標記失敗並告警 ——
        // 這裡刻意不丟例外，否則交易回滾，連 FAILED 都不會被記錄下來。
        if (payment.getAmount().compareTo(amount) != 0) {
            payment.setStatus(PaymentStatusType.FAILED);
            log.error("付款金額不符！merchantTradeNo={} 我方={} 對方={}",
                    merchantTradeNo, payment.getAmount(), amount);
            return PaymentCallbackOutcome.AMOUNT_MISMATCH;
        }

        payment.setStatus(PaymentStatusType.SUCCESS);
        payment.setGatewayTradeNo(gatewayTradeNo);
        payment.setPaidAt(paidAt);

        // 訂單狀態同樣用條件式 UPDATE，避免兩筆付款同時成功時重複轉移
        int updatedRows = orderRepository.markPaid(payment.getOrder().getId(), paidAt);
        if (updatedRows == 0) {
            // 錢收了但訂單沒有轉成 PAID —— 原因有幾種，效果一樣：
            //   ① 訂單已不是 PENDING（多半是逾時取消排程先跑到，票已回補給別人）
            //   ② 訂單還是 PENDING，但 markPaid 自己判斷已經過了 expiresAt
            //      （排程還沒跑到，但這筆訂單本來就不該再被接受付款）
            // 都是「錢收了、訂單卻沒有對應上」，一樣需要人工介入退款。
            log.error("付款成功但訂單未能轉成 PAID（已處理過或已逾時），需人工退款 orderId={} merchantTradeNo={}",
                    payment.getOrder().getId(), merchantTradeNo);
        } else {
            // ⭐ 只有「真的從 PENDING 轉成 PAID 的那一次」會走到這裡 ——
            // 重複的回呼／查詢在上面就被 markPaid 的條件擋掉了，不會重複發票
            ticketService.issueForOrder(payment.getOrder().getId());
        }
        return PaymentCallbackOutcome.APPLIED;
    }
}
