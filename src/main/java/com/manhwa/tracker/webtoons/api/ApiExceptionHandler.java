package com.manhwa.tracker.webtoons.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "Unexpected error. Please try again."
                : ex.getReason();
        return buildErrorResponse(ex.getStatusCode().value(), message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error. Please try again.", request);
    }

    private ResponseEntity<?> buildErrorResponse(int statusCode, String message, HttpServletRequest request) {
        MediaType mediaType = resolveMediaType(request);
        if (mediaType != null) {
            return ResponseEntity.status(statusCode)
                    .contentType(mediaType)
                    .body(new byte[0]);
        }
        ApiError error = new ApiError(message, LocalDateTime.now());
        return ResponseEntity.status(statusCode).body(error);
    }

    private ResponseEntity<?> buildErrorResponse(HttpStatus status, String message, HttpServletRequest request) {
        return buildErrorResponse(status.value(), message, request);
    }

    private MediaType resolveMediaType(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return null;
        }
        String path = request.getRequestURI().toLowerCase();
        if (path.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }
        if (path.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (path.endsWith(".bundle")) {
            return MediaType.parseMediaType("application/zip");
        }
        return null;
    }
}
