package com.logscope.log.domain;

import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;

/** 원본 로그와 목록·상세용 불변 읽기 표현을 정의한다. */
public record LogEvent(
    String eventId,
    Instant occurredAt,
    String serviceName,
    String instanceId,
    String level,
    String message,
    String exception) {
  /** 발생 시각을 UTC 밀리초 문자열로 표현한다. */
  public String timestamp() {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(occurredAt);
  }

  /** 예외 원문을 포함한 상세 읽기 모델을 만든다. */
  public Detail detail() {
    return new Detail(eventId, timestamp(), serviceName, instanceId, level, message, exception);
  }

  /** 예외 본문 대신 예외 유무만 담은 목록 읽기 모델을 만든다. */
  public Summary summary() {
    return new Summary(
        eventId, timestamp(), serviceName, instanceId, level, message, exception != null);
  }

  public record Detail(
      String eventId,
      String occurredAt,
      String serviceName,
      String instanceId,
      String level,
      String message,
      String exception) {}

  public record Summary(
      String eventId,
      String occurredAt,
      String serviceName,
      String instanceId,
      String level,
      String message,
      boolean hasException) {}
}
