package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.order.CreateOrderRequest;
import com.example.funeventbackend.dto.order.OrderResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.OrderService;
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
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(principal.getUser(), request));
    }

    @GetMapping("/me")
    public ResponseEntity<PagedModel<OrderResponse>> findMyOrders(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        // 包成 PagedModel：直接回傳 PageImpl 的話，JSON 結構等於暴露 Spring Data 的內部實作，
        // 升版時欄位可能改變。PagedModel 的格式是穩定的公開契約。
        return ResponseEntity.ok(
                new PagedModel<>(orderService.findByUser(principal.getUser(), pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> findMyOrder(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(orderService.findByIdAndUser(principal.getUser(), id));
    }
}
