package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.event.CreateEventRequest;
import com.example.funeventbackend.dto.event.EventResponse;
import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.dto.event.UpdateEventRequest;
import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {
    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(principal.getUser(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> update(
            @Valid @RequestBody UpdateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.update(principal.getUser(), id, request));
    }

    @PatchMapping("/{id}/publish")
    public ResponseEntity<EventResponse> publish(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.publish(principal.getUser(), id));
    }

    @GetMapping
    public ResponseEntity<PagedModel<EventSummaryResponse>> list(
            @RequestParam(required = false) Category category,
            @PageableDefault(size = 12, sort = "startAt", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        // size=12 好排格線；依 startAt 升冪 =「即將登場」，售票網站的預設語意
        return ResponseEntity.ok(new PagedModel<>(eventService.findPublished(category, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> get(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.findById(id));
    }
}
