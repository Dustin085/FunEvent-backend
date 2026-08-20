package com.example.funeventbackend.exception;

/**
 * 不符合評論資格：活動還沒開始，或這個人沒買過票。
 *
 * <p>⭐ 這條規則是「評分有意義」的前提。任何人都能評的話，
 * 分數就只是誰有空刷的結果，不再反映參加者的體驗。
 */
public class CommentNotAllowedException extends RuntimeException {
    public CommentNotAllowedException(String message) {
        super(message);
    }
}
