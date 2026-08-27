package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.favorite.FavoriteStatusResponse;
import com.example.funeventbackend.security.CustomUserDetails;
import com.example.funeventbackend.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 單一活動的收藏狀態。三支都需要登入。
 *
 * <p>⚠️ SecurityConfig 必須把這三個方法都列成 authenticated ——
 * 尤其是 GET，否則會被 {@code GET /api/events/**} 的 permitAll 蓋掉，
 * 未登入打進來 {@code principal} 是 null，直接 NPE。
 * （comments 的 eligibility 當初就踩過這個洞。）
 */
@RestController
@RequestMapping("/api/events/{eventId}/favorite")
@RequiredArgsConstructor
public class FavoriteController {
    private final FavoriteService favoriteService;

    @GetMapping
    public ResponseEntity<FavoriteStatusResponse> status(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(new FavoriteStatusResponse(
                favoriteService.isFavorited(principal.getUser(), eventId)));
    }

    /**
     * ⭐ 用 PUT 而不是 POST：這是「把狀態設成已收藏」，不是「執行一個動作」。
     *
     * <p>PUT 在 HTTP 規範上就是冪等的，而這支確實冪等（重複呼叫結果相同）——
     * 宣告正確的話，代理與重試機制可以安全地自動重試。
     * POST 規範上是非冪等的，有些重試層會刻意不重試，
     * 網路閃斷時使用者按了收藏卻靜靜地沒有生效。
     */
    @PutMapping
    public ResponseEntity<Void> add(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        favoriteService.create(principal.getUser(), eventId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ⚠️ 刪不存在的收藏也回 204 而不是 404 ——
     * 目標狀態（沒有收藏）已經達成，這是成功不是錯誤。
     */
    @DeleteMapping
    public ResponseEntity<Void> remove(
            @PathVariable Long eventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        favoriteService.delete(principal.getUser(), eventId);
        return ResponseEntity.noContent().build();
    }
}
