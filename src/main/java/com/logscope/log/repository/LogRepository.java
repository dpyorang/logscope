package com.logscope.log.repository;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.domain.LogStreamCursor;
import com.logscope.log.dto.LogResponses.LiveSnapshot;
import com.logscope.log.dto.LogResponses.StreamRow;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.support.LogStreamSignal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 원본·SSE 저장 순서·수집 outbox의 일관성을 담당한다. */
@Repository
public class LogRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final LogStreamSignal signal;
  private static final RowMapper<LogEvent> MAPPER =
      (rs, row) ->
          new LogEvent(
              rs.getString("event_id"),
              rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
              rs.getString("service_name"),
              rs.getString("instance_id"),
              rs.getString("level"),
              rs.getString("message"),
              rs.getString("exception"));

  /** 원본 DB 접근·repeatable-read 트랜잭션·저장 후 신호를 준비한다. */
  public LogRepository(
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager manager,
      LogStreamSignal signal) {
    this.jdbc = jdbc;
    this.signal = signal;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /** 로그 원본·SSE 순서·색인 outbox를 함께 commit하고 저장 후에만 구독자를 깨운다. */
  public boolean insert(LogEvent event) {
    try {
      transactions.executeWithoutResult(
          status -> {
            long sequence =
                Math.incrementExact(
                    jdbc.queryForObject(
                        "SELECT last_sequence FROM log_stream_state WHERE id = 1 FOR UPDATE",
                        Long.class));
            jdbc.update(
                "INSERT INTO log_events (event_id, occurred_at, service_name, instance_id, level, message, exception, stream_sequence) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                event.eventId(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                event.serviceName(),
                event.instanceId(),
                event.level(),
                event.message(),
                event.exception(),
                sequence);
            jdbc.update("UPDATE log_stream_state SET last_sequence = ? WHERE id = 1", sequence);
            jdbc.update("INSERT INTO log_search_outbox (event_id) VALUES (?)", event.eventId());
          });
      // The transaction is committed before waking readers.
      signal.changed();
      return true;
    } catch (DuplicateKeyException duplicate) {
      // The failed insert transaction has ended before reading the winning committed row.
      if (!find(event.eventId()).equals(event)) throw new LogApiException(409, "EVENT_ID_CONFLICT");
      return false;
    }
  }

  /** 현재 DB의 세대와 마지막 저장 확정 순서를 읽는다. */
  public LogStreamCursor head() {
    return jdbc.queryForObject(
        "SELECT epoch, last_sequence FROM log_stream_state WHERE id = 1",
        (rs, row) -> new LogStreamCursor(rs.getString(1), rs.getLong(2)));
  }

  /** 동일 repeatable-read view에서 최근 목록과 replay 시작 cursor를 함께 읽는다. */
  public LiveSnapshot snapshot() {
    return transactions.execute(
        status -> {
          // First consistent read establishes one repeatable-read view for both head and rows.
          var cursor = head();
          // Determine the time window after the read view, including the current millisecond.
          var now = snapshotEnd(Instant.now());
          var from = now.minus(java.time.Duration.ofHours(24));
          var rows = list(from, now, 50);
          return new LiveSnapshot(
              rows.stream().limit(50).map(LogEvent::summary).toList(),
              cursor.toString(),
              new java.time.format.DateTimeFormatterBuilder()
                  .appendInstant(3)
                  .toFormatter()
                  .format(from),
              new java.time.format.DateTimeFormatterBuilder()
                  .appendInstant(3)
                  .toFormatter()
                  .format(now),
              rows.size() > 50);
        });
  }

  /** 현재 밀리초에 저장된 로그까지 포함하도록 종료 경계를 다음 밀리초로 맞춘다. */
  public static Instant snapshotEnd(Instant now) {
    return now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMillis(1);
  }

  /** cursor 이후 로그를 저장 확정 순서로 최대 50건 읽는다. */
  public List<StreamRow> after(LogStreamCursor cursor) {
    return jdbc.query(
        "SELECT * FROM log_events WHERE stream_sequence > ? ORDER BY stream_sequence LIMIT 50",
        (rs, row) ->
            new StreamRow(
                new LogStreamCursor(cursor.epoch(), rs.getLong("stream_sequence")).toString(),
                MAPPER.mapRow(rs, row).summary()),
        cursor.sequence());
  }

  /** eventId로 원문을 조회하며 없으면 LOG_NOT_FOUND로 알린다. */
  public LogEvent find(String eventId) {
    List<LogEvent> rows =
        jdbc.query("SELECT * FROM log_events WHERE event_id = ?", MAPPER, eventId);
    if (rows.isEmpty()) throw new LogApiException(404, "LOG_NOT_FOUND");
    return rows.getFirst();
  }

  /** 시작 포함·끝 제외 기간을 최신순으로 읽고 잘림 감지를 위해 한 건 더 조회한다. */
  public List<LogEvent> list(Instant from, Instant to, int size) {
    return jdbc.query(
        "SELECT * FROM log_events WHERE occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at DESC, event_id DESC LIMIT ?",
        MAPPER,
        LocalDateTime.ofInstant(from, ZoneOffset.UTC),
        LocalDateTime.ofInstant(to, ZoneOffset.UTC),
        size + 1);
  }
}
