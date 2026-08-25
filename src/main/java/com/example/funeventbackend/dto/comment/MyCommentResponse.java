package com.example.funeventbackend.dto.comment;

import com.example.funeventbackend.model.Comment;

import java.time.Instant;

/**
 * 會員中心「我的評論」用。
 *
 * <p>⚠️ 不沿用 {@link CommentResponse}：那個是活動詳情頁的公開列表，
 * 帶的是 {@code userName}（誰寫的）；這裡的每一則都是自己寫的，
 * 需要的反而是「評的是哪個活動」。消費者不同 → DTO 不同。
 */
public record MyCommentResponse(
        Long id,
        /** 讓列表可以點回該活動 */
        Long eventId,
        String eventName,
        Integer rating,
        String content,
        Instant createdAt
) {
    public static MyCommentResponse from(Comment comment) {
        return new MyCommentResponse(
                comment.getId(),
                comment.getEvent().getId(),
                comment.getEvent().getName(),
                comment.getRating(),
                comment.getContent(),
                comment.getCreatedAt());
    }
}
