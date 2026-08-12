package com.example.funeventbackend.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

public record CreateOrderRequest(
        @NotEmpty(message = "訂單至少要有一個項目")
        @Valid
        List<Item> items
) {
    public record Item(
            @NotNull(message = "票種 id 不能為空")
            Long ticketTypeId,

            @NotNull(message = "數量不能為空")
            @Min(value = 1, message = "數量至少為 1")
            Integer quantity
    ) {
    }

    // 同一個票種重複出現會導致同一列被扣兩次，直接擋在驗證層
    @AssertTrue(message = "同一個票種不能重複出現")
    public boolean isTicketTypeIdUnique() {
        if (items == null) {
            return true;
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(Item::ticketTypeId)
                .distinct()
                .count() == items.size();
    }
}
