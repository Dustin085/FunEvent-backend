package com.example.funeventbackend.dto.ticket;

import com.example.funeventbackend.model.TicketType;

import java.math.BigDecimal;
import java.time.Instant;

public record TicketTypeResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer capacity,
        Integer stock,
        Instant saleStartAt,
        Instant saleEndAt
) {
    public static TicketTypeResponse from(TicketType ticketType) {
        return new TicketTypeResponse(
                ticketType.getId(),
                ticketType.getName(),
                ticketType.getDescription(),
                ticketType.getPrice(),
                ticketType.getCapacity(),
                ticketType.getStock(),
                ticketType.getSaleStartAt(),
                ticketType.getSaleEndAt()
        );
    }
}
