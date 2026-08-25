package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.comment.MyCommentResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 會員中心的「我的評論」。需要登入 —— SecurityConfig 沒把 /api/users/**
 * 放進 permitAll，落在 anyRequest().authenticated()。
 *
 * <p>⚠️ 為什麼獨立一個 controller：
 * <ul>
 *   <li>{@code CommentController} 的 base path 是 /api/events/&#123;eventId&#125;/comments，
 *       這支掛不上去</li>
 *   <li>{@code UserController} 已經被記在技術債裡「職責過多」，
 *       再注入 CommentService 只會更糟</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users/me/comments")
@RequiredArgsConstructor
public class UserCommentController {
    private final CommentService commentService;

    /**
     * ⚠️ 排序帶 id 當第二鍵：createdAt 有並列值時資料庫回傳的順序不保證穩定，
     * 分頁時同一則可能出現在兩頁、或兩頁都漏掉。理由同 CommentController.list
     */
    @GetMapping
    public ResponseEntity<PagedModel<MyCommentResponse>> listMine(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PageableDefault(size = 10, sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                new PagedModel<>(commentService.findMine(principal.getUser(), pageable)));
    }
}
