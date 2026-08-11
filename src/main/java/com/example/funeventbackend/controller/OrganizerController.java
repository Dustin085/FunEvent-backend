package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.organizer.CreateOrganizerRequest;
import com.example.funeventbackend.dto.organizer.OrganizerResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.OrganizerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizers")
@RequiredArgsConstructor
public class OrganizerController {
    private final OrganizerService organizerService;

    @PostMapping
    public ResponseEntity<OrganizerResponse> create(
            @Valid @RequestBody CreateOrganizerRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(organizerService.create(principal.getUser(), request));
    }
}
