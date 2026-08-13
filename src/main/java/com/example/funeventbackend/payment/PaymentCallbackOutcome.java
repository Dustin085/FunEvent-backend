package com.example.funeventbackend.payment;

/**
 * 回呼處理的結果。
 * <p>
 * 四種結果都會回成功給金流商（回錯誤只會讓它不斷重送），
 * 但我方需要區分實際做了什麼 —— 這既是可觀測性，也讓冪等性成為可斷言的行為。
 */
public enum PaymentCallbackOutcome {
    APPLIED,          // 這次回呼真的完成了付款
    DUPLICATE,        // 重複回呼，什麼都沒改
    PAYMENT_FAILED,   // 金流商回報付款失敗
    AMOUNT_MISMATCH,  // 金額與我方記錄不符
}
