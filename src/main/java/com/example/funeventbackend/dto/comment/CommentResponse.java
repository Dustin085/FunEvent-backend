package com.example.funeventbackend.dto.comment;

import com.example.funeventbackend.model.Comment;

import java.time.Instant;

/**
 * ⚠️ 不回傳 userId、email 等資訊 —— 評論是公開的，
 * 只需要顯示名稱。多回傳的欄位都是白送給爬蟲的個資。
 */
public record CommentResponse(
        Long id,
        String userName,
        Integer rating,
        String content,
        Instant createdAt
) {
    public static CommentResponse from(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getUser().getName(),
                comment.getRating(),
                comment.getContent(),
                comment.getCreatedAt());
    }
}
