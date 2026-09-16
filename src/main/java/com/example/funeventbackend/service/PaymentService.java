package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.payment.PaymentInitiationResponse;
import com.example.funeventbackend.exception.InvalidPaymentCallbackException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.payment.PaymentCallbackOutcome;
import com.example.funeventbackend.payment.PaymentCallbackResult;
import com.example.funeventbackend.payment.PaymentGateway;
import com.example.funeventbackend.payment.PaymentInitiation;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private static final String ORDER_NOT_FOUND_MESSAGE = "找不到此訂單";
    private static final String ORDER_NOT_PAYABLE_MESSAGE = "此訂單目前無法付款";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    // 收到回呼／查到結果之後，實際寫進資料庫的共用邏輯，見那個 class 開頭的說明
    private final PaymentResultApplier paymentResultApplier;
    // 訂單過期時，initiate 要能立即處理（查詢確認、取消回補庫存），不用等排程
    private final PaymentReconciliationService paymentReconciliationService;

    /** 付款嘗試自己的期限，跟 app.order.payment-timeout 是兩個獨立的時鐘 */
    @Value("${app.payment.timeout}")
    private Duration paymentTimeout;

    // ⚠️ noRollbackFor 不是可有可無的：訂單過期、沒有活著的付款時，
    // 這支方法會先觸發取消（真的取消、真的回補庫存），然後才丟
    // InvalidStateTransitionException 告訴呼叫端「這次不能付款」。
    // Spring 預設遇到 unchecked exception 會把整個交易 rollback ——
    // 連剛剛才做的取消跟回補庫存都會被撤銷，等於白做工，訂單還是卡在 PENDING。
    // 這跟 RT 竊用偵測踩過的坑是同一個模式：不能讓「回報失敗」的例外
    // 把「已經完成的正確處理」一起吃掉。
    @Transactional(noRollbackFor = InvalidStateTransitionException.class)
    public PaymentInitiationResponse initiate(User user, Long orderId) {
        // 查詢條件含 user：不是你的訂單直接 404
        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND_MESSAGE));
        if (order.getStatus() != OrderStatusType.PENDING) {
            throw new InvalidStateTransitionException(ORDER_NOT_PAYABLE_MESSAGE);
        }

        Instant now = Instant.now();
        if (order.getExpiresAt().isBefore(now)) {
            // ⚠️ 訂單已經過期，但排程可能還沒處理到（scan-interval 可以長達一小時）。
            // 與其等排程，這裡直接判斷能不能立刻處理 —— 但前提是沒有其他還活著的
            // 付款嘗試：如果有，代表使用者之前按過付款、那筆付款自己的期限還沒到，
            // 這裡貿然取消會把它連根拔起，重演「付完款才發現票被還回去」的問題，
            // 只是換了個進入點。
            //
            // 沒有活著的付款時，也不能直接取消了事 —— 之前按過的那筆付款
            // 即使自己的期限已經過了，也不代表綠界那邊真的沒收到錢，唯一能確定
            // 的方法是查一次，見 PaymentReconciliationService。
            if (!paymentRepository.existsLivePaymentForOrder(orderId, now)) {
                paymentReconciliationService.reconcileAndCancelIfUnpaid(orderId);
            }
            throw new InvalidStateTransitionException(ORDER_NOT_PAYABLE_MESSAGE);
        }

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(generateMerchantTradeNo())
                .amount(order.getTotalAmount())
                .expiresAt(now.plus(paymentTimeout))
                .build());

        // ⚠️ initiate 只做參數組裝與簽章，不打外部 HTTP。
        // 若未來的金流商需要先呼叫 API 換取付款連結，那段必須移到交易外，
        // 否則一次外部往返就會把交易（連同它握著的鎖）拉長好幾秒。
        PaymentInitiation initiation = paymentGateway.initiate(order, payment);

        return new PaymentInitiationResponse(
                payment.getId(),
                payment.getMerchantTradeNo(),
                initiation.paymentUrl(),
                initiation.formFields());
    }

    /**
     * ⚠️ 不是 {@code @Transactional}：實際的資料庫寫入在
     * {@code paymentResultApplier.apply} 自己的交易裡完成，這裡只負責解析回呼。
     */
    public PaymentCallbackOutcome handleCallback(Map<String, String> params) {
        PaymentCallbackResult result = paymentGateway.parseCallback(params)
                .orElseThrow(() -> new InvalidPaymentCallbackException("付款回呼驗證失敗"));

        // 回呼是即時的，「現在」就是付款完成的時間，兩者沒有實質差距
        return paymentResultApplier.apply(
                result.merchantTradeNo(), result.success(), result.amount(),
                result.gatewayTradeNo(), Instant.now(), params.toString());
    }

    /**
     * 產生商店交易編號。三個要求：
     * <ol>
     *   <li>唯一 —— DB 的 unique 索引是最後防線</li>
     *   <li>不可預測 —— 流水號會把你的訂單量洩漏給競爭對手</li>
     *   <li>長度受限且只含英數 —— 多數金流商有 20 字元上限</li>
     * </ol>
     */
    private String generateMerchantTradeNo() {
        StringBuilder sb = new StringBuilder("FE");
        sb.append(Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase());
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
