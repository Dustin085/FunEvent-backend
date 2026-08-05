package com.example.funeventbackend.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,

        // 沒有欄位錯誤時，這個 key 不會出現在 JSON 裡
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FieldError> errors
) {
    /** 單一欄位的錯誤。同一欄位可能有多筆（例如密碼同時太短又缺數字）。 */
    public record FieldError(String field, String message) {}

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return of(status, message, path, List.of());
    }

    public static ErrorResponse of(HttpStatus status, String message, String path,
                                   List<FieldError> errors) {
        return new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                Instant.now(),
                errors
        );
    }
}