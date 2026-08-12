package com.example.funeventbackend.dto.ticket;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateTicketTypeRequest(
        @NotBlank(message = "票種名稱不能為空")
        String name,

        String description,

        @NotNull(message = "票價不能為空")
        @DecimalMin(value = "0.00", message = "票價不能為負數")
        @Digits(integer = 8, fraction = 2, message = "票價格式不正確")
        BigDecimal price,

        @NotNull(message = "票券總量不能為空")
        @Min(value = 1, message = "票券總量至少為 1")
        Integer capacity,

        Instant saleStartAt,

        Instant saleEndAt
) {
    // 跨欄位驗證：兩個時間都有填時，結束必須晚於開始
    @AssertTrue(message = "販售結束時間必須晚於開始時間")
    public boolean isSaleWindowValid() {
        return saleStartAt == null || saleEndAt == null || saleEndAt.isAfter(saleStartAt);
    }
}
