package com.example.funeventbackend.dto.order;

import com.example.funeventbackend.model.OrderStatusType;

import java.math.BigDecimal;

/** GROUP BY 查詢的一列。只在 Service 內部用來組成 EventSalesSummary */
public record EventSalesByStatus(
        OrderStatusType status,
        Long quantity,
        BigDecimal amount
) {
}
