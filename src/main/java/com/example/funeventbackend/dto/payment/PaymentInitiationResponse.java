package com.example.funeventbackend.dto.payment;

import java.util.Map;

public record PaymentInitiationResponse(
        Long paymentId,
        String merchantTradeNo,
        String paymentUrl,
        Map<String, String> formFields
) {
}
