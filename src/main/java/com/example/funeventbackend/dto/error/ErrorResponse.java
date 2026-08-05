package com.example.funeventbackend.dto.error;

import java.time.LocalDateTime;

public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        LocalDateTime timestamp
) {
    // 工廠方法，方便快速建立 ErrorResponse
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, LocalDateTime.now());
    }

    // 工廠方法，建立常用的 bad request
    public static ErrorResponse badRequest(String message, String path) {
        return new ErrorResponse(400, "Bad Requset", message, path, LocalDateTime.now());
    }
}
