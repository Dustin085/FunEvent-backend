package com.example.funeventbackend.dto.category;

import com.example.funeventbackend.model.Category;

/**
 * @param code 給程式用：篩選參數、推導圖示檔名
 * @param name 給人看
 */
public record CategoryResponse(String code, String name) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.name(), category.getDisplayName());
    }
}
