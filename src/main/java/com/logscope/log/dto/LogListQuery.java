package com.logscope.log.dto;

import com.logscope.log.exception.LogApiException;
import com.logscope.log.validation.LogInput;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/** 정적 목록 조회에 필요한 기간과 크기를 보관한다. */
public record LogListQuery(Instant from, Instant to, int size) {
  /** 정적 목록의 기간·크기와 지원하지 않는 필터·페이지 조건을 검증한다. */
  public static LogListQuery parse(MultiValueMap<String, String> params) {
    for (String key : params.keySet()) {
      if (!Set.of("from", "to", "size", "instanceId", "level", "keyword", "cursor").contains(key))
        throw new LogApiException("query", "UNKNOWN_FIELD");
      if (params.get(key).size() != 1) throw new LogApiException(key, "DUPLICATE_PARAMETER");
      if (params.getFirst(key).isBlank()) throw new LogApiException(key, "INVALID_FORMAT");
    }
    var from = LogInput.time(params.getFirst("from"), "from");
    var to = LogInput.time(params.getFirst("to"), "to");
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0)
      throw new LogApiException("to", "OUT_OF_RANGE");
    int size = 50;
    if (params.containsKey("size")) {
      try {
        size = Integer.parseInt(params.getFirst("size"));
      } catch (NumberFormatException e) {
        throw new LogApiException("size", "INVALID_FORMAT");
      }
      if (size < 1 || size > 100) throw new LogApiException("size", "OUT_OF_RANGE");
    }
    for (String pending : List.of("instanceId", "level", "keyword", "cursor")) {
      if (params.containsKey(pending))
        throw new LogApiException(501, "QUERY_FEATURE_NOT_IMPLEMENTED");
    }
    return new LogListQuery(from, to, size);
  }
}
