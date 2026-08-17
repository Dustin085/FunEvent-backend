package com.example.funeventbackend.model;

import lombok.Getter;

/**
 * 活動分類，固定八大類，使用者不能自行新增。
 * <p>
 * 用 enum 而不是資料表，理由跟 {@link City} 相同：集合固定、沒有 CRUD 需求、
 * 而且要拿來當查詢條件 —— 自由文字會讓同一個分類出現多種寫法。
 * <p>
 * ⚠️ <b>宣告順序就是首頁分類卡片的排列順序</b>（{@code values()} 依宣告序回傳）。
 * 圖示不放在這裡：那是呈現層的事，前端用常數名推出檔名
 * （{@code MUSIC_GROOVE} → {@code /images/category/music-groove.svg}）。
 */
@Getter
public enum Category {
    NATURE_SCIENCE("自然科學"),
    LANGUAGE("多元語言"),
    ART_CULTURE("藝術人文"),
    SPORT("活力運動"),
    LIFE_EXPERIENCE("生活體驗"),
    DIGITAL_SCIENCE("數位科技"),
    CREATIVE_DIY("創意手作"),
    MUSIC_GROOVE("音樂律動");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }
}
