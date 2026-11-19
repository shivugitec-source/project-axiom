package io.projectaxiom.bootstrap.security;

import io.projectaxiom.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts a tenant selector/path mismatch into an authorization response. */
@RestControllerAdvice
public class TenantContextExceptionHandler {
    @ExceptionHandler(TenantContext.TenantContextMismatchException.class)
    public ResponseEntity<Void> handleTenantContextMismatch() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
