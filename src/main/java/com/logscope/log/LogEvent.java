package com.logscope.log;

import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;

record LogEvent(
    String eventId,
    Instant occurredAt,
    String serviceName,
    String instanceId,
    String level,
    String message,
    String exception) {
  String timestamp() {
    return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(occurredAt);
  }

  Detail detail() {
    return new Detail(eventId, timestamp(), serviceName, instanceId, level, message, exception);
  }

  Summary summary() {
    return new Summary(
        eventId, timestamp(), serviceName, instanceId, level, message, exception != null);
  }

  record Detail(
      String eventId,
      String occurredAt,
      String serviceName,
      String instanceId,
      String level,
      String message,
      String exception) {}

  record Summary(
      String eventId,
      String occurredAt,
      String serviceName,
      String instanceId,
      String level,
      String message,
      boolean hasException) {}
}
