package com.logscope.log;

import java.util.List;

class LogApiException extends RuntimeException {
  final int status;
  final String code;
  final List<FieldError> errors;

  LogApiException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
    this.errors = List.of();
  }

  LogApiException(String field, String code) {
    super("VALIDATION_ERROR");
    this.status = 400;
    this.code = "VALIDATION_ERROR";
    this.errors = List.of(new FieldError(field, code));
  }

  record FieldError(String field, String code) {}
}
