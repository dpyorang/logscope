package com.logscope.log;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {LogController.class, LogLiveController.class, LogSearchController.class})
class LogErrorHandler {
  @ExceptionHandler(LogApiException.class)
  ResponseEntity<Problem> api(LogApiException error) {
    return response(error.status, error.code, error.errors);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Problem> media() {
    return response(415, "UNSUPPORTED_MEDIA_TYPE", List.of());
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Problem> storage() {
    return response(503, "STORAGE_UNAVAILABLE", List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> unexpected() {
    return response(500, "INTERNAL_ERROR", List.of());
  }

  private ResponseEntity<Problem> response(
      int status, String code, List<LogApiException.FieldError> errors) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            new Problem(
                "about:blank", code, status, code, "The request could not be completed.", errors));
  }

  record Problem(
      String type,
      String title,
      int status,
      String code,
      String detail,
      List<LogApiException.FieldError> errors) {}
}
