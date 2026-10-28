package com.logscope.log.dto;

import com.logscope.log.exception.LogApiException;
import com.logscope.log.validation.LogInput;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/** 검색 요청의 검증된 값만 보관하며 ES 전송 형식과 분리한다. */
public record LogSearchQuery(
    Instant from, Instant to, String instanceId, String level, String keyword, int size) {
  /** 검색 조건의 단일 값·UTC 기간·식별자·레벨·검색어 길이를 검증한다. */
  public static LogSearchQuery parse(MultiValueMap<String, String> params) {
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
}
