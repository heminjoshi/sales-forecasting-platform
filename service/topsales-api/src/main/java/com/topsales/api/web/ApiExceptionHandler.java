package com.topsales.api.web;

import com.topsales.api.error.TenantMismatchException;
import com.topsales.api.error.UnknownTenantException;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps the read path's failures to RFC 7807 {@code application/problem+json} (docs/lld.md §14).
 * Spring serializes a returned {@link ProblemDetail} with the right status and content type
 * automatically. Reads never 5xx for a degraded forecast — the body's {@code status} tells the
 * truth (Phase 4); these handlers cover only genuine client errors.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String TYPE_BASE = "https://topsales/errors/";

    @ExceptionHandler(TenantMismatchException.class)
    public ProblemDetail handleTenantMismatch(TenantMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "tenant-mismatch", "Tenant mismatch", ex.getMessage(), request);
    }

    @ExceptionHandler(UnknownTenantException.class)
    public ProblemDetail handleUnknownTenant(UnknownTenantException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "unknown-tenant", "Unknown tenant", ex.getMessage(), request);
    }

    /**
     * 400s: bad enum (Mode/Window factory throws IllegalArgumentException), k out of range
     * (IllegalArgumentException), or a non-integer / missing required param (Spring's binding
     * exceptions).
     */
    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ProblemDetail handleBadRequest(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", ex.getMessage(), request);
    }

    private ProblemDetail problem(
            HttpStatus status, String slug, String title, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug));
        pd.setTitle(title);
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }
}
