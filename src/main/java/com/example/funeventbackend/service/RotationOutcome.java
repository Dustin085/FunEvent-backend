package com.example.funeventbackend.service;

import com.example.funeventbackend.model.User;

/**
 * 換票（{@link RefreshTokenService#rotate}）的結果。
 *
 * <p>⭐ 為什麼用回傳值而不是丟例外：竊用偵測必須同時做兩件事 ——
 * 「撤銷整條 family」（寫入）和「拒絕這次請求」。丟例外會讓交易回滾，
 * 撤銷跟著不見。
 *
 * <p>早期是靠 {@code REQUIRES_NEW} 的獨立交易繞過去，直到 {@code rotate}
 * 加上悲觀鎖之後 —— 那個獨立交易會去 UPDATE 外層正鎖著的同一列，
 * <b>直接死鎖</b>（{@code RefreshTokenRotationTest} 抓到過）。
 *
 * <p>改成回傳結果之後，撤銷與拒絕都在同一個交易裡正常提交，
 * 例外由沒有交易的 {@code AuthController} 丟。沒有隱藏耦合，也不可能忘記加標註。
 *
 * <p>⚠️ 用 sealed interface 而不是 enum + 欄位：兩種結果**攜帶的資料不一樣**
 *（成功帶 token 與 user，拒絕什麼都不帶）。用 enum 的話那兩個欄位在拒絕時
 * 是沒有意義的 null，而編譯器擋不住你去讀它們 —— 型別就不再表達規則了。
 * sealed 也讓 switch 有窮盡性檢查：之後多一種結果，編譯器會逼你處理。
 *
 * <p>⚠️ {@code Rejected} 刻意不帶原因：對外一律是同一句「驗證失敗」，
 * 區分「token 不存在／已過期／被撤銷」等於告訴攻擊者他猜到了哪一步。
 *
 * <p>⚠️ 放在 {@code service} 而不是 {@code dto}：它帶的是 {@link User} entity，
 * 是服務層內部的型別，不是給 API 回傳的形狀。
 */
public sealed interface RotationOutcome {

    record Rotated(String rawToken, User user) implements RotationOutcome {
    }

    record Rejected() implements RotationOutcome {
    }
}
