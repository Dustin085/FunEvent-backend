package com.example.funeventbackend.dto.comment;

/**
 * @param average 平均分。⚠️ 沒有任何評論時是 null 而不是 0.0 ——
 *                「沒人評過」和「大家都給 0 分」對使用者是兩件完全不同的事，
 *                前端據此顯示「尚無評價」
 * @param count   則數
 */
public record RatingSummary(Double average, long count) {
}
