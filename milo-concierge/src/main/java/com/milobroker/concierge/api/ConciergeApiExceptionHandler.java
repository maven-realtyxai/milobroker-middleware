package com.milobroker.concierge.api;
import jakarta.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
/** Stable error body for Concierge APIs, built with JSON-P. */
@RestControllerAdvice
public class ConciergeApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<String> responseStatus(ResponseStatusException ex, HttpServletRequest request) {
    return body(ex.getStatusCode().value(), ex.getReason(), request);
  }
  @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class,
      MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
      IllegalArgumentException.class})
  public ResponseEntity<String> badRequest(Exception ex, HttpServletRequest request) {
    return body(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), request);
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<String> unexpected(Exception ex, HttpServletRequest request) {
    return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Concierge request failed", request);
  }
  private ResponseEntity<String> body(int status, String message, HttpServletRequest request) {
    String error = message == null ? "Request failed" : message;
    String json = Json.createObjectBuilder()
      .add("status", status)
      .add("error", error)
      .add("path", request.getRequestURI())
      .add("timestamp", Instant.now().toString())
      .build().toString();
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
  }
}
