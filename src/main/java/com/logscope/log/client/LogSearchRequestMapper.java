package com.logscope.log.client;

import com.logscope.log.dto.LogSearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 검색 조건을 Elasticsearch Query DSL로 변환한다. */
public final class LogSearchRequestMapper {
  /** 직접 생성을 막고 정적 변환만 사용한다. */
  private LogSearchRequestMapper() {}

  /** 검증한 검색 조건을 정확한 필터·Nori AND 검색·최신순 정렬의 ES 요청으로 바꾼다. */
  public static Map<String, Object> body(LogSearchQuery query) {
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
                        .format(query.from()),
                    "lt",
                    new java.time.format.DateTimeFormatterBuilder()
                        .appendInstant(3)
                        .toFormatter()
                        .format(query.to())))));
    if (query.instanceId() != null)
      filters.add(Map.of("term", Map.of("instanceId", query.instanceId())));
    if (query.level() != null) filters.add(Map.of("term", Map.of("level", query.level())));
    // match analyzes text; user input never becomes query_string or raw Query DSL.
    if (query.keyword() != null)
      filters.add(
          Map.of("match", Map.of("message", Map.of("query", query.keyword(), "operator", "and"))));
    return Map.of(
        "size",
        query.size() + 1,
        "track_total_hits",
        false,
        "sort",
        List.of(Map.of("occurredAt", "desc"), Map.of("eventId", "desc")),
        "query",
        Map.of("bool", Map.of("filter", filters)));
  }
}
