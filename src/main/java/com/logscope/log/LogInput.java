package com.logscope.log;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class LogInput {
  private static final Set<String> FIELDS =
      Set.of("eventId", "occurredAt", "serviceName", "instanceId", "level", "message", "exception");
  private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

  private LogInput() {}

  static LogEvent parse(JsonNode node) {
    if (node == null || !node.isObject()) throw new LogApiException("body", "INVALID_FORMAT");
    for (String name : node.propertyNames()) {
      if (!FIELDS.contains(name)) throw new LogApiException("body", "UNKNOWN_FIELD");
    }
    String id = uuid(text(node, "eventId", true));
    Instant time = time(text(node, "occurredAt", true), "occurredAt");
    String service = identifier(text(node, "serviceName", true), "serviceName");
    String instance = identifier(text(node, "instanceId", true), "instanceId");
    String level = text(node, "level", true);
    if (!LEVELS.contains(level)) throw new LogApiException("level", "INVALID_ENUM");
    String message = bounded(text(node, "message", true), "message", 4096);
    String exception = bounded(text(node, "exception", false), "exception", 32768);
    return new LogEvent(id, time, service, instance, level, message, exception);
  }

  private static String text(JsonNode node, String field, boolean required) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      if (required) throw new LogApiException(field, "REQUIRED");
      return null;
    }
    if (!value.isString()) throw new LogApiException(field, "INVALID_FORMAT");
    String text = value.asString();
    if (required && text.isBlank()) throw new LogApiException(field, "REQUIRED");
    return text;
  }

  static String uuid(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw new LogApiException("eventId", "INVALID_FORMAT");
    return value;
  }

  static Instant time(String value, String field) {
    if (value == null) throw new LogApiException(field, "REQUIRED");
    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z"))
      throw new LogApiException(field, "INVALID_UTC_TIMESTAMP");
    try {
      // LocalDateTime parsing rejects leap seconds and invalid calendar dates rather than
      // normalizing.
      var date = java.time.LocalDateTime.parse(value.substring(0, value.length() - 1));
      if (date.getYear() < 1000) throw new LogApiException(field, "OUT_OF_RANGE");
      return date.toInstant(java.time.ZoneOffset.UTC);
    } catch (DateTimeException e) {
      throw new LogApiException(field, "INVALID_UTC_TIMESTAMP");
    }
  }

  private static String identifier(String value, String field) {
    if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}"))
      throw new LogApiException(field, "INVALID_FORMAT");
    return value;
  }

  private static String bounded(String value, String field, int max) {
    if (value == null) return null;
    if (value.isBlank()) throw new LogApiException(field, "REQUIRED");
    if (value.codePointCount(0, value.length()) > max) throw new LogApiException(field, "TOO_LONG");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw new LogApiException(field, "INVALID_FORMAT");
      } else if (Character.isLowSurrogate(c)) throw new LogApiException(field, "INVALID_FORMAT");
    }
    return value;
  }
}
