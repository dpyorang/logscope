package com.logscope.log.dto;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.exception.LogApiException;
import java.util.List;

/** 공용 API·SSE 응답 형태를 묶어 구현 계층에서 독립시킨다. */
public final class LogResponses {
  /** 직접 생성을 막고 정적 변환만 사용한다. */
  private LogResponses() {}

  public record Rejected(int index, String code, List<LogApiException.FieldError> errors) {}

  public record BatchReceipt(int stored, int duplicates, List<Rejected> rejected) {}

  public record Receipt(String eventId, String status) {}

  public record Listing(
      List<LogEvent.Summary> items, int size, String nextCursor, String previousCursor) {}

  public record SearchResult(
      List<LogEvent.Summary> items, int size, boolean truncated, Synchronization synchronization) {}

  public record Synchronization(long pendingCount, long retryingCount, boolean indexingPending) {}

  public record StreamRow(String cursor, LogEvent.Summary item) {}

  public record LiveSnapshot(
      List<LogEvent.Summary> items, String cursor, String from, String to, boolean truncated) {}
}
