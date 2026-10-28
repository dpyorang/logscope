package com.logscope.log.exception;

import java.util.List;

/** 명세에 정의한 상태·코드·필드 오류를 안전하게 전달한다. */
public class LogApiException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<FieldError> errors;

  /** 호출자에게 노출할 오류 의미와 원인을 보존한다. */
  public LogApiException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
    this.errors = List.of();
  }

  /** 호출자에게 노출할 오류 의미와 원인을 보존한다. */
  public LogApiException(String field, String code) {
    super("VALIDATION_ERROR");
    this.status = 400;
    this.code = "VALIDATION_ERROR";
    this.errors = List.of(new FieldError(field, code));
  }

  /** 외부에 반환할 HTTP 상태를 제공한다. */
  public int status() {
    return status;
  }

  /** API 명세의 안전한 오류 코드를 제공한다. */
  public String code() {
    return code;
  }

  /** 검증에 실패한 필드와 오류 코드 목록을 제공한다. */
  public List<FieldError> errors() {
    return errors;
  }

  public record FieldError(String field, String code) {}
}
