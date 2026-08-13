package com.example.funeventbackend.service;

import com.example.funeventbackend.dto.payment.PaymentInitiationResponse;
import com.example.funeventbackend.exception.InvalidPaymentCallbackException;
import com.example.funeventbackend.exception.InvalidStateTransitionException;
import com.example.funeventbackend.exception.ResourceNotFoundException;
import com.example.funeventbackend.model.Order;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.model.Payment;
import com.example.funeventbackend.model.PaymentStatusType;
import com.example.funeventbackend.model.User;
import com.example.funeventbackend.payment.PaymentCallbackOutcome;
import com.example.funeventbackend.payment.PaymentCallbackResult;
import com.example.funeventbackend.payment.PaymentGateway;
import com.example.funeventbackend.payment.PaymentInitiation;
import com.example.funeventbackend.repository.OrderRepository;
import com.example.funeventbackend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private static final String ORDER_NOT_FOUND_MESSAGE = "找不到此訂單";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    @Transactional
    public PaymentInitiationResponse initiate(User user, Long orderId) {
        // 查詢條件含 user：不是你的訂單直接 404
        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new ResourceNotFoundException(ORDER_NOT_FOUND_MESSAGE));
        if (order.getStatus() != OrderStatusType.PENDING) {
            throw new InvalidStateTransitionException("此訂單目前無法付款");
        }

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .merchantTradeNo(generateMerchantTradeNo())
                .amount(order.getTotalAmount())
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

    @Transactional
    public PaymentCallbackOutcome handleCallback(Map<String, String> params) {
        PaymentCallbackResult result = paymentGateway.parseCallback(params)
                .orElseThrow(() -> new InvalidPaymentCallbackException("付款回呼驗證失敗"));

        // 悲觀鎖：鎖住之後「讀狀態 → 判斷 → 寫」在 Java 裡才是安全的
        Payment payment = paymentRepository.findByMerchantTradeNoForUpdate(result.merchantTradeNo())
                .orElseThrow(() -> new InvalidPaymentCallbackException("找不到對應的付款記錄"));

        // ⭐ 冪等：重複的回呼在這裡就返回，不再改任何資料，而且回應仍然是成功。
        // 金流商收不到成功回應會不斷重送，第二次回錯誤只會讓它一直重試。
        if (payment.getStatus() != PaymentStatusType.PENDING) {
            log.info("重複的付款回呼，已忽略 merchantTradeNo={}", result.merchantTradeNo());
            return PaymentCallbackOutcome.DUPLICATE;
        }

        payment.setRawCallback(params.toString());

        if (!result.success()) {
            payment.setStatus(PaymentStatusType.FAILED);
            return PaymentCallbackOutcome.PAYMENT_FAILED;
        }

        // 金額只拿來比對，不拿來更新。不符就標記失敗並告警 ——
        // 這裡刻意不丟例外，否則交易回滾，連 FAILED 都不會被記錄下來。
        if (payment.getAmount().compareTo(result.amount()) != 0) {
            payment.setStatus(PaymentStatusType.FAILED);
            log.error("付款金額不符！merchantTradeNo={} 我方={} 回呼={}",
                    result.merchantTradeNo(), payment.getAmount(), result.amount());
            return PaymentCallbackOutcome.AMOUNT_MISMATCH;
        }

        Instant now = Instant.now();
        payment.setStatus(PaymentStatusType.SUCCESS);
        payment.setGatewayTradeNo(result.gatewayTradeNo());
        payment.setPaidAt(now);

        // 訂單狀態同樣用條件式 UPDATE，避免兩筆付款同時成功時重複轉移
        int updatedRows = orderRepository.markPaid(payment.getOrder().getId(), now);
        if (updatedRows == 0) {
            // 錢收了但訂單已不是 PENDING（多半是逾時被取消，票已回補給別人）。
            // 這是真實世界一定會發生的情況，必須人工介入退款。
            log.error("付款成功但訂單狀態已非 PENDING，需人工退款 orderId={} merchantTradeNo={}",
                    payment.getOrder().getId(), result.merchantTradeNo());
        }
        return PaymentCallbackOutcome.APPLIED;
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
