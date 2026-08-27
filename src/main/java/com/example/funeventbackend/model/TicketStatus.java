package com.example.funeventbackend.model;

public enum TicketStatus {
    /** 尚未使用，可以入場 */
    VALID,
    /** 已核銷入場。⚠️ 這是終點，不能改回 VALID —— 否則一張票可以重複入場 */
    USED,
    /**
     * 作廢（退款、主辦者取消活動）。
     *
     * <p>⚠️ 目前<b>沒有任何地方會設定這個值</b> —— 退款功能還沒做。
     * 先放進 enum 是因為核銷邏輯本來就要處理「不是 VALID」的情況，
     * 之後做退款時不必改 schema。
     */
    VOID
}
