package com.example.funeventbackend.dto.payment;

import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(
        @NotNull(message = "訂單 id 不能為空")
        Long orderId
) {
}
