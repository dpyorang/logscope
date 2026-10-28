package com.logscope.log;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

record LogSearchQuery(
    Instant from, Instant to, String instanceId, String level, String keyword, int size) {
  static LogSearchQuery parse(MultiValueMap<String, String> params) {
    for (String key : params.keySet()) {
      if (!Set.of("from", "to", "instanceId", "level", "keyword", "size").contains(key))
        throw new LogApiException("query", "UNKNOWN_FIELD");
      if (params.get(key).size() != 1) throw new LogApiException(key, "DUPLICATE_PARAMETER");
      if (params.getFirst(key) == null || params.getFirst(key).isBlank())
        throw new LogApiException(key, "REQUIRED");
    }
    var from = LogInput.time(params.getFirst("from"), "from");
    var to = LogInput.time(params.getFirst("to"), "to");
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0)
      throw new LogApiException("to", "OUT_OF_RANGE");
    String instance = params.getFirst("instanceId"),
        level = params.getFirst("level"),
        keyword = params.getFirst("keyword");
    if (instance != null && !instance.matches("[a-z0-9][a-z0-9._-]{0,63}"))
      throw new LogApiException("instanceId", "INVALID_FORMAT");
    if (level != null && !Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(level))
      throw new LogApiException("level", "INVALID_ENUM");
    if (keyword != null) {
      keyword = keyword.strip();
      if (keyword.codePointCount(0, keyword.length()) > 200)
        throw new LogApiException("keyword", "TOO_LONG");
      for (int i = 0; i < keyword.length(); i++) {
        char c = keyword.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (++i >= keyword.length() || !Character.isLowSurrogate(keyword.charAt(i)))
            throw new LogApiException("keyword", "INVALID_FORMAT");
        } else if (Character.isLowSurrogate(c))
          throw new LogApiException("keyword", "INVALID_FORMAT");
      }
    }
    int size = 50;
    if (params.containsKey("size")) {
      if (!params.getFirst("size").matches("[0-9]{1,3}"))
        throw new LogApiException("size", "INVALID_FORMAT");
      size = Integer.parseInt(params.getFirst("size"));
      if (size < 1 || size > 100) throw new LogApiException("size", "OUT_OF_RANGE");
    }
    return new LogSearchQuery(from, to, instance, level, keyword, size);
  }

  Map<String, Object> body() {
    var filters = new ArrayList<Map<String, Object>>();
    filters.add(
        Map.of(
            "range",
            Map.of(
                "occurredAt",
                Map.of(
                    "gte",
                    new java.time.format.DateTimeFormatterBuilder()
                        .appendInstant(3)
                        .toFormatter()
                        .format(from),
                    "lt",
                    new java.time.format.DateTimeFormatterBuilder()
                        .appendInstant(3)
                        .toFormatter()
                        .format(to)))));
    if (instanceId != null) filters.add(Map.of("term", Map.of("instanceId", instanceId)));
    if (level != null) filters.add(Map.of("term", Map.of("level", level)));
    // match analyzes text; user input never becomes query_string or raw Query DSL.
    if (keyword != null)
      filters.add(Map.of("match", Map.of("message", Map.of("query", keyword, "operator", "and"))));
    return Map.of(
        "size",
        size + 1,
        "track_total_hits",
        false,
        "sort",
        List.of(Map.of("occurredAt", "desc"), Map.of("eventId", "desc")),
        "query",
        Map.of("bool", Map.of("filter", filters)));
  }
}
