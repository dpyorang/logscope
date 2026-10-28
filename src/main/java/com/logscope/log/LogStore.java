package com.logscope.log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class LogStore {
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

  LogStore(
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager manager,
      LogStreamSignal signal) {
    this.jdbc = jdbc;
    this.signal = signal;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  boolean insert(LogEvent event) {
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

  LogStreamCursor head() {
    return jdbc.queryForObject(
        "SELECT epoch, last_sequence FROM log_stream_state WHERE id = 1",
        (rs, row) -> new LogStreamCursor(rs.getString(1), rs.getLong(2)));
  }

  LiveSnapshot snapshot() {
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

  static Instant snapshotEnd(Instant now) {
    return now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMillis(1);
  }

  List<StreamRow> after(LogStreamCursor cursor) {
    return jdbc.query(
        "SELECT * FROM log_events WHERE stream_sequence > ? ORDER BY stream_sequence LIMIT 50",
        (rs, row) ->
            new StreamRow(
                new LogStreamCursor(cursor.epoch(), rs.getLong("stream_sequence")).toString(),
                MAPPER.mapRow(rs, row).summary()),
        cursor.sequence());
  }

  record StreamRow(String cursor, LogEvent.Summary item) {}

  record LiveSnapshot(
      List<LogEvent.Summary> items, String cursor, String from, String to, boolean truncated) {}

  LogEvent find(String eventId) {
    List<LogEvent> rows =
        jdbc.query("SELECT * FROM log_events WHERE event_id = ?", MAPPER, eventId);
    if (rows.isEmpty()) throw new LogApiException(404, "LOG_NOT_FOUND");
    return rows.getFirst();
  }

  List<LogEvent> list(Instant from, Instant to, int size) {
    return jdbc.query(
        "SELECT * FROM log_events WHERE occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at DESC, event_id DESC LIMIT ?",
        MAPPER,
        LocalDateTime.ofInstant(from, ZoneOffset.UTC),
        LocalDateTime.ofInstant(to, ZoneOffset.UTC),
        size + 1);
  }
}
