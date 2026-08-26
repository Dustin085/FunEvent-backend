package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.comment.CommentEligibilityResponse;
import com.example.funeventbackend.dto.comment.CommentResponse;
import com.example.funeventbackend.dto.comment.CreateCommentRequest;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.CommentService;
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
@RequestMapping("/api/events/{eventId}/comments")
@RequiredArgsConstructor
public class CommentController {
    private final CommentService commentService;

    /**
     * 評論列表。公開 —— SecurityConfig 的 GET /api/events/** 已經涵蓋。
     *
     * <p>⚠️ 排序帶 id 當第二鍵：createdAt 有並列值時資料庫回傳的順序不保證穩定，
     * 分頁時同一則可能出現在兩頁、或兩頁都漏掉。
     */
    @GetMapping
    public ResponseEntity<PagedModel<CommentResponse>> list(
            @PathVariable Long eventId,
            @PageableDefault(size = 10, sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                new PagedModel<>(commentService.findByEvent(eventId, pageable)));
    }

    /** 需要登入。資格檢查（買過票、活動已開始）在 Service 裡 */
    @PostMapping
    public ResponseEntity<CommentResponse> create(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.create(principal.getUser(), eventId, request));
    }

    /**
     * 需要登入。「我現在能不能評論這個活動」。
     *
     * <p>⭐ 前端靠這支決定要顯示表單還是說明 —— 資格規則仍然只寫在後端一處，
     * 前端不是自己算而是來問。沒買票的人看到一張填完才被 403 打回票的表單，
     * 是很糟的體驗。
     */
    @GetMapping("/eligibility")
    public ResponseEntity<CommentEligibilityResponse> eligibility(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(
                commentService.checkEligibility(principal.getUser(), eventId));
    }

    /**
     * 需要登入。查「我」對這個活動的評論。
     *
     * <p>⚠️ 目前前端沒有呼叫這支 —— 判斷「評過了沒」已經改用上面的 eligibility。
     * 留著是因為它是之後做「編輯評論」時載入原內容的天然端點。
     */
    @GetMapping("/me")
    public ResponseEntity<CommentResponse> me(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(commentService.findMyComment(principal.getUser(), eventId));
    }
}
