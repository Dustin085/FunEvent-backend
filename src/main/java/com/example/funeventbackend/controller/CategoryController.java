package com.example.funeventbackend.controller;

import com.example.funeventbackend.dto.category.CategoryResponse;
import com.example.funeventbackend.model.Category;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    /**
     * 八大分類的清單。前端的分類卡片與篩選選單都從這裡拿 ——
     * 分類名稱只存在後端一份，不必在前端再維護一份對照表。
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list() {
        return ResponseEntity.ok(
                Arrays.stream(Category.values()).map(CategoryResponse::from).toList());
    }
}
