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

    @GetMapping
    public ResponseEntity<List<TicketTypeResponse>> list(@PathVariable Long eventId) {
        return ResponseEntity.status(HttpStatus.OK).body(ticketTypeService.findByEventId(eventId));
    }
}
