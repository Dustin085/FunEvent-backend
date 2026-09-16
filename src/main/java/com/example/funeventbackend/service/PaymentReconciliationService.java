package com.example.funeventbackend.service;

import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.PaymentStatusType;
import com.example.funeventbackend.payment.PaymentGateway;
import com.example.funeventbackend.payment.PaymentQueryResult;
import com.example.funeventbackend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 取消一筆訂單之前，先確認有沒有漏接的付款成功回呼。
 *
 * <p>被兩個地方呼叫：{@code OrderExpiryScheduler}（排程掃到的過期訂單）與
 * {@code PaymentService.initiate}（使用者按下付款時才發現訂單已過期，
 * 不想等排程跑到）——兩邊共用同一套「查詢、決定取消或套用」邏輯，不是各寫一份。
 *
 * <p>獨立成自己的 class 是為了解開一個循環依賴：這支要呼叫
 * {@code OrderService.cancelExpiredOrder} 跟 {@code PaymentResultApplier.apply}
 * （兩者都要跨 bean 呼叫才能讓 {@code @Transactional} 生效）。如果把這段邏輯放進
 * {@code PaymentService} 裡，{@code PaymentService.initiate} 呼叫它就會變成同一個
 * class 內部呼叫，代理失效；放進 {@code OrderService} 又會反過來要 OrderService
 * 認識 PaymentGateway。獨立出來，{@code PaymentService} 只需要單向依賴這支，
 * 不會形成循環。
 *
 * <p>⚠️ 呼叫端要自己先確認這筆訂單沒有「活著」的付款（自己的 expiresAt 還沒到）——
 * 這支方法不做那個判斷，它假設呼叫端已經篩過，只負責「這些已經過了自己期限的
 * 付款，有沒有可能其實成功了」。排程用 {@code OrderRepository.findExpiredPendingIds}
 * 的 {@code NOT EXISTS} 條件篩過；{@code initiate} 用
 * {@code PaymentRepository.existsLivePaymentForOrder} 篩過。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentResultApplier paymentResultApplier;
    private final OrderService orderService;

    /**
     * ⚠️ 刻意不是 {@code @Transactional}：迴圈裡有真正對外的 HTTP 呼叫
     * （{@code paymentGateway.queryStatus}），不能讓交易連同資料庫連線一起卡在
     * 等待金流商回應上。實際的資料庫寫入分別在 {@code paymentResultApplier.apply}
     * 與 {@code orderService.cancelExpiredOrder} 各自的交易裡完成，這支只負責
     * 協調順序。
     *
     * @return true 代表訂單真的被取消了；false 代表查到已付款（不該取消）、
     * 查詢失敗保守不取消，或訂單早就不是 PENDING 了
     */
    public boolean reconcileAndCancelIfUnpaid(Long orderId) {
        for (Payment pending : paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatusType.PENDING)) {
            Optional<PaymentQueryResult> queried = paymentGateway.queryStatus(pending.getMerchantTradeNo());
            if (queried.isEmpty()) {
                // 查詢本身失敗（連不上、逾時、解析不了）：不知道真實狀態，
                // 不能當成「查到了、沒付款」，保守地這次先不取消
                log.warn("查詢付款狀態失敗，這次先不取消訂單 orderId={} merchantTradeNo={}",
                        orderId, pending.getMerchantTradeNo());
                return false;
            }
            PaymentQueryResult result = queried.get();
            if (result.paid()) {
                log.warn("取消前查到綠界其實已付款，回呼可能漏接 orderId={} merchantTradeNo={}",
                        orderId, pending.getMerchantTradeNo());
                // rawCallback 傳 null —— 這不是回呼，沒有原始回呼內容可以存證
                paymentResultApplier.apply(pending.getMerchantTradeNo(), true, result.amount(),
                        result.gatewayTradeNo(), result.paidAt(), null);
                return false;
            }
        }
        return orderService.cancelExpiredOrder(orderId);
    }
}
