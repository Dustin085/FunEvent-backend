package com.example.funeventbackend.dto.comment;

/**
 * 「我現在能不能評論這個活動」。
 *
 * <p>⭐ 存在的理由：資格規則（活動開始了嗎、買過票嗎、評過了嗎）只寫在後端一處，
 * 但前端需要知道結果才能決定要顯示表單還是說明 ——
 * 讓沒買過票的人填完整張表單、按下送出才被 403 打回票，是很糟的體驗。
 *
 * <p>所以前端不是「自己算」而是「來問」。同一條規則仍然只有一份。
 */
public record CommentEligibilityResponse(
        boolean canComment,
        /** ⚠️ canComment 為 true 時是 null。這是給前端挑文案用的，不是錯誤碼 */
        Reason reason
) {
    public enum Reason {
        /** 活動還沒開始 */
        NOT_STARTED,
        /** 沒有這個活動的已付款訂單 */
        NOT_ATTENDED,
        /** 已經評論過了 */
        ALREADY_COMMENTED
    }

    public static CommentEligibilityResponse allowed() {
        return new CommentEligibilityResponse(true, null);
    }

    public static CommentEligibilityResponse blockedBy(Reason reason) {
        return new CommentEligibilityResponse(false, reason);
    }
}
