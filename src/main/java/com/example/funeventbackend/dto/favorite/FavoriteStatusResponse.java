package com.example.funeventbackend.dto.favorite;

/**
 * 「我有沒有收藏這個活動」。
 *
 * <p>⚠️ 用 record 包起來而不是直接回一個裸的 boolean ——
 * 裸的 {@code true} 之後想加欄位（例如收藏時間、收藏總數）就是破壞性變更。
 */
public record FavoriteStatusResponse(boolean favorited) {
}
