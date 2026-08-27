package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.event.EventSummaryResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.FavoriteService;
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
 * 會員中心的「我的收藏」。需要登入 —— 落在 {@code anyRequest().authenticated()}。
 *
 * <p>⚠️ 為什麼獨立一個 controller：{@code FavoriteController} 的 base path 是
 * /api/events/&#123;eventId&#125;/favorite，這支掛不上去。理由同 comments 那兩支。
 */
@RestController
@RequestMapping("/api/users/me/favorites")
@RequiredArgsConstructor
public class UserFavoriteController {
    private final FavoriteService favoriteService;

    /**
     * ⚠️ 排序的是 {@code Favorite.createdAt}（收藏時間），不是活動時間 ——
     * 使用者對「我最近收藏了什麼」的預期是收藏順序。
     *
     * <p>⚠️ 帶 id 當第二鍵：createdAt 有並列值時資料庫回傳的順序不保證穩定，
     * 分頁時同一筆可能出現在兩頁、或兩頁都漏掉。
     *
     * <p>size 12 對齊首頁與搜尋頁的一頁筆數（卡片是三欄排版）。
     */
    @GetMapping
    public ResponseEntity<PagedModel<EventSummaryResponse>> listMine(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PageableDefault(size = 12, sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                new PagedModel<>(favoriteService.findMine(principal.getUser(), pageable)));
    }
}
