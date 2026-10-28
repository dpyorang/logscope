package com.logscope.log.exception;

import com.logscope.log.controller.LogController;
import com.logscope.log.controller.LogLiveController;
import com.logscope.log.controller.LogSearchController;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 로그 API의 예외를 내부 정보 없는 공통 HTTP 오류로 변환한다. */
@RestControllerAdvice(
    assignableTypes = {LogController.class, LogLiveController.class, LogSearchController.class})
public class LogErrorHandler {
  /** 명세에 정의된 실패를 problem+json 응답으로 변환한다. */
  @ExceptionHandler(LogApiException.class)
  public ResponseEntity<Problem> api(LogApiException error) {
    return response(error.status(), error.code(), error.errors());
  }

  /** 지원하지 않는 미디어 타입을 415 오류로 변환한다. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Problem> media() {
    return response(415, "UNSUPPORTED_MEDIA_TYPE", List.of());
  }

  /** 저장소 장애를 원문 정보 없는 503 오류로 변환한다. */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<Problem> storage() {
    return response(503, "STORAGE_UNAVAILABLE", List.of());
  }

  /** 예상하지 못한 예외의 내부 정보를 숨기고 500 오류를 반환한다. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> unexpected() {
    return response(500, "INTERNAL_ERROR", List.of());
  }

  /** 안전한 공통 오류 응답 형식을 생성한다. */
  private ResponseEntity<Problem> response(
      int status, String code, List<LogApiException.FieldError> errors) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            new Problem(
                "about:blank", code, status, code, "The request could not be completed.", errors));
  }

  public record Problem(
      String type,
      String title,
      int status,
      String code,
      String detail,
      List<LogApiException.FieldError> errors) {}
}
