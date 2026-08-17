package com.example.funeventbackend.dto.event;

import com.example.funeventbackend.model.Category;
import com.example.funeventbackend.model.City;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

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

        String address
) {
}
