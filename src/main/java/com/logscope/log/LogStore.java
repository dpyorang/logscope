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

  LogStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
  }

  boolean insert(LogEvent event) {
    try {
      transactions.executeWithoutResult(
          status ->
              jdbc.update(
                  "INSERT INTO log_events (event_id, occurred_at, service_name, instance_id, level, message, exception) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  event.eventId(),
                  LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                  event.serviceName(),
                  event.instanceId(),
                  event.level(),
                  event.message(),
                  event.exception()));
      return true;
    } catch (DuplicateKeyException duplicate) {
      // The failed insert transaction has ended before reading the winning committed row.
      if (!find(event.eventId()).equals(event)) throw new LogApiException(409, "EVENT_ID_CONFLICT");
      return false;
    }
  }

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
