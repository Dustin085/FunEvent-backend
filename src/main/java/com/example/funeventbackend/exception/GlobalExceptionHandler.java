package com.example.funeventbackend.exception;

import com.example.funeventbackend.dto.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
@Slf4j // logger
public class GlobalExceptionHandler {
    // EmailAlreadyExistsException
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExistsException(
            EmailAlreadyExistsException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // CommentNotAllowedException：不符合評論資格（沒買過票、活動還沒開始）
    @ExceptionHandler(CommentNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleCommentNotAllowedException(
            CommentNotAllowedException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // AlreadyCommentedException：重複評論同一個活動
    @ExceptionHandler(AlreadyCommentedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyCommentedException(
            AlreadyCommentedException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // OAuthProviderUnavailableException：連不上第三方，或第三方回了 5xx。
    // ⚠️ 刻意不歸成 401 —— 那不是使用者的憑證有問題，是我們或 Google 出問題
    @ExceptionHandler(OAuthProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleOAuthProviderUnavailableException(
            OAuthProviderUnavailableException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidOAuthTokenException：第三方登入的憑證無效
    @ExceptionHandler(InvalidOAuthTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthTokenException(
            InvalidOAuthTokenException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // OAuthAccountLinkConflictException：email 已被本站帳號使用，但未經第三方驗證，不能自動綁定
    @ExceptionHandler(OAuthAccountLinkConflictException.class)
    public ResponseEntity<ErrorResponse> handleOAuthAccountLinkConflictException(
            OAuthAccountLinkConflictException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // OAuthOnlyAccountException：帳號沒有密碼（第三方登入建立的），卻用密碼登入
    @ExceptionHandler(OAuthOnlyAccountException.class)
    public ResponseEntity<ErrorResponse> handleOAuthOnlyAccountException(
            OAuthOnlyAccountException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidStateTransitionException
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransitionException(
            InvalidStateTransitionException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // MethodArgumentTypeMismatchException：query 或 path 參數轉不成目標型別，
    // 例如 ?category=不存在的分類、/api/events/abc。
    // 沒有這個 handler 的話會掉進 catch-all 變成 500 —— 但那是使用者輸入錯誤，不是伺服器故障
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        // 不要把 e.getMessage() 直接吐出去 —— 它含有 Java 類別名與套件路徑
        ErrorResponse response = ErrorResponse.of(
                status,
                "參數「" + e.getName() + "」的值不正確",
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidPaymentCallbackException
    @ExceptionHandler(InvalidPaymentCallbackException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentCallbackException(
            InvalidPaymentCallbackException e,
            HttpServletRequest request
    ) {
        // 回呼端點對外開放，驗簽失敗可能代表有人在嘗試偽造付款 —— 一律留下紀錄
        log.warn("付款回呼被拒絕 [{}]：{}", request.getRequestURI(), e.getMessage());
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InsufficientStockException
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStockException(
            InsufficientStockException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // ResourceNotFoundException
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // ResourceAccessDeniedException
    @ExceptionHandler(ResourceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessDeniedException(
            ResourceAccessDeniedException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidPasswordException：已登入，但請求裡的「目前密碼」不對。
    // ⚠️ 是 400 不是 401 —— 401 的語意是「你還沒通過驗證」，
    // 而這支端點的呼叫者已經通過了。詳見該例外類別的說明
    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPasswordException(
            InvalidPasswordException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidCredentialsException
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
            InvalidCredentialsException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // MethodArgumentNotValidException，@Vaild 驗證失敗時拋出
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        List<ErrorResponse.FieldError> fieldErrorList =
                e.getFieldErrors()
                        .stream()
                        .map(fieldError ->
                                new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                        .toList();
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = ErrorResponse.of(
                status,
                "輸入資料驗證失敗",
                request.getRequestURI(),
                fieldErrorList
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidResetTokenException
    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetTokenException(
            InvalidResetTokenException e,
            HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidEventDataException
    @ExceptionHandler(InvalidEventDataException.class)
    public ResponseEntity<ErrorResponse> handleInvalidEventDataException(
            InvalidEventDataException e,
            HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // InvalidRefreshTokenException，無效 refresh token
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshTokenException(
            InvalidRefreshTokenException e,
            HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // DataIntegrityViolationException，違反資料完整性約束
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException e,
            HttpServletRequest request
    ) {
        log.warn("資料完整性約束被觸發 [{}]", request.getRequestURI(), e);
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(status, "資料重複或違反限制", request.getRequestURI());
        return ResponseEntity.status(status).body(response);
    }

    // OrganizerAlreadyExistsException
    @ExceptionHandler(OrganizerAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleOrganizerAlreadyExistsException(
            OrganizerAlreadyExistsException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    // NotOrganizerException
    @ExceptionHandler(NotOrganizerException.class)
    public ResponseEntity<ErrorResponse> handleNotOrganizerException(
            NotOrganizerException e,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        ErrorResponse response = ErrorResponse.of(
                status,
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }

    /*
     *  非預期例外 (Exception)
     * */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception e,
            HttpServletRequest request
    ) {
        // 在後端 log 出這個例外
        log.error("未預期的例外 [{}]", request.getRequestURI(), e);
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "伺服器內部錯誤",
                request.getRequestURI()

        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
