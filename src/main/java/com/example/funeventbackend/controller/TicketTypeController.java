package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.ticket.CreateTicketTypeRequest;
import com.example.funeventbackend.dto.ticket.TicketTypeResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.TicketTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events/{eventId}/ticket-types")
@RequiredArgsConstructor
public class TicketTypeController {
    private final TicketTypeService ticketTypeService;

    @PostMapping
    public ResponseEntity<TicketTypeResponse> create(
            @Valid @RequestBody CreateTicketTypeRequest request,
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long eventId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ticketTypeService.create(principal.getUser(), eventId, request));
    }

    /**
     * 刪除票種。
     *
     * <p>⚠️ 路徑帶著 eventId，Service 會確認票種真的屬於它 ——
     * 只驗父資源就放行的話，帶自己的 eventId 加別人的 ticketTypeId 就能刪別人的東西。
     */
    @DeleteMapping("/{ticketTypeId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long eventId,
            @PathVariable Long ticketTypeId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        ticketTypeService.delete(principal.getUser(), eventId, ticketTypeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<TicketTypeResponse>> list(@PathVariable Long eventId) {
        return ResponseEntity.status(HttpStatus.OK).body(ticketTypeService.findByEventId(eventId));
    }
}
