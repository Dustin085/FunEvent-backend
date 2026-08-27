package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.event.OrganizerEventDetailResponse;
import com.example.funeventbackend.dto.event.OrganizerEventSummaryResponse;
import com.example.funeventbackend.dto.order.EventOrderItemResponse;
import com.example.funeventbackend.dto.order.EventSalesSummary;
import com.example.funeventbackend.dto.organizer.CreateOrganizerRequest;
import com.example.funeventbackend.dto.organizer.OrganizerResponse;
import com.example.funeventbackend.dto.organizer.UpdateOrganizerRequest;
import com.example.funeventbackend.dto.ticket.CheckInRequest;
import com.example.funeventbackend.dto.ticket.CheckInResponse;
import com.example.funeventbackend.model.EventStatus;
import com.example.funeventbackend.model.OrderStatusType;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.EventService;
import com.example.funeventbackend.service.OrderService;
import com.example.funeventbackend.service.OrganizerService;
import com.example.funeventbackend.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主辦者自己的資源。
 *
 * <p>⚠️ 這裡的每一支都只給擁有者看，和公開的 /api/events 是**相反的安全預設**。
 * 刻意分成兩組端點而不是「同一支依身分決定可見性」——
 * 後者要在每個讀取路徑都記得判斷，漏一個就洩漏草稿。
 */
@RestController
@RequestMapping("/api/organizers")
@RequiredArgsConstructor
public class OrganizerController {
    private final OrganizerService organizerService;
    private final EventService eventService;
    private final OrderService orderService;
    private final TicketService ticketService;

    @PostMapping
    public ResponseEntity<OrganizerResponse> create(
            @Valid @RequestBody CreateOrganizerRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(organizerService.create(principal.getUser(), request));
    }

    /**
     * 目前使用者的主辦者身分。
     *
     * <p>⚠️ 不是主辦者時回 403（NotOrganizerException）。前端把它當成
     * 「還沒有主辦者身分」而不是錯誤 —— 和 getCurrentUser() 遇到 401
     * 回傳 null 是同一個模式。
     */
    @GetMapping("/me")
    public ResponseEntity<OrganizerResponse> getMine(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(organizerService.getMine(principal.getUser()));
    }

    /**
     * 修改主辦單位的名稱與介紹。
     *
     * <p>⚠️ 不是主辦者時同樣回 403 —— 沒有身分就沒有東西可以改。
     */
    /**
     * 核銷一張票（入場掃描）。
     *
     * <p>⚠️ 回傳 200 + 結果物件，即使是「已使用」或「無效」——
     * 對現場掃票的人來說那些不是錯誤，是需要看清楚的結果。
     * 見 {@link CheckInResponse}。
     */
    /**
     * 預覽掃到的票，<b>不改狀態</b>。前端用它顯示「確認核銷王小明的一般票嗎」。
     *
     * <p>⚠️ 這只是預測 —— 真正的把關在 check-in 的條件式 UPDATE。
     */
    @PostMapping("/me/events/{eventId}/check-in/preview")
    public ResponseEntity<CheckInResponse> previewCheckIn(
            @PathVariable Long eventId,
            @Valid @RequestBody CheckInRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                ticketService.preview(principal.getUser(), eventId, request.token()));
    }

    @PostMapping("/me/events/{eventId}/check-in")
    public ResponseEntity<CheckInResponse> checkIn(
            @PathVariable Long eventId,
            @Valid @RequestBody CheckInRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                ticketService.checkIn(principal.getUser(), eventId, request.token()));
    }

    @PatchMapping("/me")
    public ResponseEntity<OrganizerResponse> updateMine(
            @Valid @RequestBody UpdateOrganizerRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                organizerService.update(principal.getUser(), request));
    }

    /**
     * 我的活動列表，含草稿與已取消。
     *
     * <p>⚠️ 預設依 createdAt 遞減 —— 後台的心智模型是「我最近在弄什麼」，
     * 和公開列表的「即將登場」（startAt 遞增）完全相反。
     * 帶 id 當第二排序鍵，避免同毫秒建立的活動在分頁時漂移。
     */
    @GetMapping("/me/events")
    public ResponseEntity<PagedModel<OrganizerEventSummaryResponse>> listMyEvents(
            @RequestParam(required = false) EventStatus status,
            @PageableDefault(size = 20, sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(new PagedModel<>(
                eventService.findMine(principal.getUser(), status, pageable)));
    }

    /** 編輯頁用：活動與票種一次拿齊，不必打兩支端點 */
    @GetMapping("/me/events/{eventId}")
    public ResponseEntity<OrganizerEventDetailResponse> getMyEvent(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                eventService.findMineById(principal.getUser(), eventId));
    }

    /**
     * 某個活動的銷售明細。
     *
     * <p>⚠️ 回的是「訂單明細」不是「訂單」—— 一筆訂單可以跨活動下單，
     * 用訂單當單位的話主辦者會看到別人活動的資料。
     *
     * <p>預設依建立時間遞減：後台想先看到最新的成交。
     */
    @GetMapping("/me/events/{eventId}/orders")
    public ResponseEntity<PagedModel<EventOrderItemResponse>> listEventOrders(
            @PathVariable Long eventId,
            @RequestParam(required = false) OrderStatusType status,
            @PageableDefault(size = 20, sort = {"order.createdAt", "id"},
                    direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(new PagedModel<>(orderService.findEventOrders(
                principal.getUser(), eventId, status, pageable)));
    }

    /** 銷售摘要：已售出張數／金額／待付款張數 */
    @GetMapping("/me/events/{eventId}/sales-summary")
    public ResponseEntity<EventSalesSummary> getSalesSummary(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                orderService.getEventSalesSummary(principal.getUser(), eventId));
    }
}
