package com.milobroker.atlas.api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
/** Stable error body for every Atlas API: machine-readable status plus a human message. */
@RestControllerAdvice
public class AtlasApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex, HttpServletRequest request) {
    return body(ex.getStatusCode().value(), ex.getReason(), request);
  }
  @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
  public ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest request) {
    return body(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), request);
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
    return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Atlas request failed", request);
  }
  private ResponseEntity<Map<String, Object>> body(int status, String message, HttpServletRequest request) {
    return ResponseEntity.status(status).body(Map.of(
      "status", status,
      "error", message == null ? "Request failed" : message,
      "path", request.getRequestURI(),
      "timestamp", Instant.now().toString()));
  }
}
