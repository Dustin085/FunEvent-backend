package com.example.funeventbackend.dto.comment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotNull(message = "評分不可為空")
        @Min(value = 1, message = "評分最低 1 分")
        @Max(value = 5, message = "評分最高 5 分")
        Integer rating,

        @Size(max = 2000, message = "評論最多 2000 字")
        String content
) {
}
