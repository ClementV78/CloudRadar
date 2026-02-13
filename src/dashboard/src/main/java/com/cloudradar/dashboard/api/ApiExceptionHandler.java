package com.cloudradar.dashboard.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized REST exception mapping for dashboard API endpoints.
 *
 * <p>Known domain and backend exceptions are converted into stable JSON error payloads.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * Maps request validation errors to HTTP 400.
   *
   * @param ex validation exception thrown by parsing or service logic
   * @return standardized error payload
   */
  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
    return ResponseEntity.badRequest().body(error("bad_request", ex.getMessage()));
  }

  /**
   * Maps resource-not-found conditions to HTTP 404.
   *
   * @param ex missing-resource exception
   * @return standardized error payload
   */
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("not_found", ex.getMessage()));
  }

  /**
   * Maps unmatched routes to HTTP 404 instead of generic 500.
   *
   * @param ex Spring MVC no-resource/no-handler exception
   * @return standardized not-found payload
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleMissingRoute(Exception ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(error("not_found", "resource not found"));
  }

  /**
   * Maps Redis access failures to HTTP 502.
   *
   * @param ex backend data access failure
   * @return standardized error payload
   */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(error("backend_unavailable", "redis backend unavailable"));
  }

  /**
   * Maps unexpected failures to HTTP 500.
   *
   * @param ex unhandled server-side exception
   * @return standardized error payload
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(error("internal_error", "internal server error"));
  }

  private Map<String, Object> error(String code, String message) {
    return Map.of(
        "error", code,
        "message", message,
        "timestamp", Instant.now().toString());
  }
}
