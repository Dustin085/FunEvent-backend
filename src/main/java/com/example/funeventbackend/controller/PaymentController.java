package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.payment.CreatePaymentRequest;
import com.example.funeventbackend.dto.payment.PaymentInitiationResponse;
import com.example.funeventbackend.payment.PaymentCallbackOutcome;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentInitiationResponse> initiate(
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.initiate(principal.getUser(), request.orderId()));
    }

    /**
     * 金流商伺服器對伺服器的回呼。這是唯一可信的付款證明 ——
     * 使用者瀏覽器導回的參數可以被偽造，絕不能拿來標記訂單已付款。
     * <p>
     * 這個端點是 permitAll（金流商不會帶我們的 JWT），安全性完全靠
     * {@code PaymentGateway.parseCallback} 的驗簽。
     * <p>
     * 回應必須是金流商指定的純文字格式，收不到就會不斷重送。
     */
    @PostMapping(value = "/callback")
    public ResponseEntity<String> callback(@RequestParam Map<String, String> params) {
        PaymentCallbackOutcome outcome = paymentService.handleCallback(params);
        // 四種結果都要回成功給金流商，但要留下我們這邊實際做了什麼
        log.info("付款回呼處理結果：{}", outcome);
        return ResponseEntity.ok("1|OK");
    }
}
