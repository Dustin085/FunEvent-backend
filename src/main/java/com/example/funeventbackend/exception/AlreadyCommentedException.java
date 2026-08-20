package com.example.funeventbackend.exception;

/** 同一個人對同一個活動重複評論。由 UNIQUE(event_id, user_id) 擋下 */
public class AlreadyCommentedException extends RuntimeException {
    public AlreadyCommentedException(String message) {
        super(message);
    }
}
