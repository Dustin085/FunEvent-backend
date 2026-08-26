package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record UpdateEventRequest(
        @NotBlank(message = "名稱不能為空")
        String name,

        @NotBlank(message = "介紹不能為空")
        String description,

        @NotNull(message = "開始時間不能為空")
        Instant startAt,

        @NotNull(message = "結束時間不能為空")
        Instant endAt,

        @NotNull(message = "分類不能為空")
        Category category,

        @NotNull(message = "縣市不能為空")
        City city,

        String district,

        String locationName,

        String address,

        /**
         * 圖片網址，順序就是顯示順序，第一張是封面。
         *
         * <p>⚠️ 這是<b>全量取代</b>：送什麼就是什麼，沒送的會被刪掉。
         * 送 null 或空清單等於「這個活動沒有圖片」。
         * 規則與 {@link CreateEventRequest#imageUrls()} 相同。
         */
        @Size(max = 10, message = "圖片最多 10 張")
        List<
                @NotBlank(message = "圖片網址不能為空")
                @Pattern(regexp = "^https://\\S+$", message = "圖片網址必須是 https 開頭")
                @Size(max = 500, message = "圖片網址過長")
                String> imageUrls
) {
}
