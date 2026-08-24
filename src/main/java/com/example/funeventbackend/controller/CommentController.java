package com.example.funeventbackend.controller;

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
     * 需要登入。查「我」對這個活動有沒有評論過 —— 前端用來決定要顯示
     * 表單、已評論過的訊息，還是自己那則評論
     */
    @GetMapping("/me")
    public ResponseEntity<CommentResponse> me(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(commentService.findMyComment(principal.getUser(), eventId));
    }
}
