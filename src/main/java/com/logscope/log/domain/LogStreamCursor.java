package com.logscope.log.domain;

import com.logscope.log.exception.LogApiException;

/** DB 세대와 저장 확정 순서로 재연결 위치를 표현한다. */
public record LogStreamCursor(String epoch, long sequence) {
  /** DB 세대와 저장 순서로 이루어진 replay cursor의 형식·숫자 범위를 검증한다. */
  public static LogStreamCursor parse(String value) {
    if (value == null
        || value.length() > 56
        || !value.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]{0,18})"))
      throw new LogApiException("after", "INVALID_FORMAT");
    try {
      int colon = value.indexOf(':');
      return new LogStreamCursor(
          value.substring(0, colon), Long.parseLong(value.substring(colon + 1)));
    } catch (NumberFormatException error) {
      throw new LogApiException("after", "OUT_OF_RANGE");
    }
  }

  /** 다른 DB 세대나 아직 저장되지 않은 미래 순서로의 복구를 거절한다. */
  public void validate(LogStreamCursor head) {
    if (!epoch.equals(head.epoch)) throw new LogApiException(409, "LIVE_CURSOR_INVALID");
    if (sequence > head.sequence) throw new LogApiException(409, "LIVE_CURSOR_AHEAD");
  }

  /** 재연결에 사용할 세대:순서 문자열을 만든다. */
  @Override
  public String toString() {
    return epoch + ":" + sequence;
  }
}
