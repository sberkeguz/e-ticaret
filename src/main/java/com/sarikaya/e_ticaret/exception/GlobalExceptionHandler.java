package com.sarikaya.e_ticaret.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

// Controller'lardan fırlatılan hataları yakalayıp uygun HTTP cevabına çevirir.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Bizim özel hatalarımız (404 kayıt yok, 403 kullanıcı adı alınmış, 403 zayıf şifre...)
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return build(e.getStatus(), e.getCode(), e.getMessage());
    }

    // DTO doğrulama hataları (ör. şifre 5 karakterden kısa)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("İstek doğrulanamadı.");

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    // Hatalı şifre -> 401.
    // Spring, "kullanıcı yok" durumunu da buna çevirir; böylece hangisinin yanlış olduğu sızmaz.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Kullanıcı adı veya şifre hatalı.");
    }

    // isActive = false olan hesap giriş yapmaya çalışırsa
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException e) {
        return build(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Hesabınız pasif durumda.");
    }

    // Olmayan adres -> 404
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoEndpoint(Exception e) {
        return build(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "Aradığınız sayfa bulunamadı.");
    }

    // Bozuk JSON veya @FlexibleBoolean'ın reddettiği değer -> 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "İstek gövdesi geçersiz.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Zorunlu istek parametresi eksik: " + e.getParameterName() + ".");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Zorunlu istek başlığı eksik: " + e.getHeaderName() + ".");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Bu istek yöntemi bu adres için desteklenmiyor.");
    }

    // Metot seviyesinde yetki hatası (@PreAuthorize) -> 403
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Bu işlem için yetkiniz yok.");
    }

    // Yukarıdakilerin hiçbirine uymayan her şey -> 500.
    // Gerçek hatayı istemciye göstermiyoruz ama konsola yazıyoruz ki bulabilelim.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception e) {
        log.error("Beklenmeyen hata", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Bilinmeyen bir sistem hatası oluştu.");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, message, Instant.now()));
    }
}