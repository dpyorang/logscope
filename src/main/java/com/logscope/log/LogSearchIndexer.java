package com.logscope.log;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class LogSearchIndexer {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final LogSearchClient client;
  private final LogStore store;
  private long retryAfter;

  LogSearchIndexer(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      LogSearchClient client,
      LogStore store) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.client = client;
    this.store = store;
    transactions.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  synchronized void runCycle() {
    if (!client.enabled() || System.nanoTime() < retryAfter) return;
    try {
      transactions.executeWithoutResult(
          tx -> {
            // Serialize index creation/rebuild and consumption across app instances, not log
            // ingestion.
            String previous =
                jdbc.queryForObject(
                    "SELECT index_uuid FROM log_search_state WHERE id=1 FOR UPDATE", String.class);
            String uuid = client.identity(store.head().epoch(), true);
            if (!uuid.equals(previous)) {
              jdbc.update(
                  "INSERT INTO log_search_outbox (event_id) SELECT event_id FROM log_events ON DUPLICATE KEY UPDATE attempts=0, next_attempt_at='1000-01-01 00:00:00.000', last_error=NULL");
              jdbc.update(
                  "UPDATE log_search_state SET index_uuid=?,last_success_at=NULL WHERE id=1", uuid);
            }
            var pending =
                jdbc.query(
                    "SELECT e.event_id,e.occurred_at,e.service_name,e.instance_id,e.level,e.message,(e.exception IS NOT NULL) AS has_exception,o.attempts FROM log_search_outbox o JOIN log_events e ON e.event_id=o.event_id WHERE o.next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY e.stream_sequence LIMIT 50",
                    (rs, row) ->
                        new Pending(
                            new LogEvent.Summary(
                                rs.getString("event_id"),
                                new java.time.format.DateTimeFormatterBuilder()
                                    .appendInstant(3)
                                    .toFormatter()
                                    .format(
                                        rs.getObject("occurred_at", LocalDateTime.class)
                                            .toInstant(ZoneOffset.UTC)),
                                rs.getString("service_name"),
                                rs.getString("instance_id"),
                                rs.getString("level"),
                                rs.getString("message"),
                                rs.getBoolean("has_exception")),
                            rs.getInt("attempts")));
            if (pending.isEmpty()) return;
            List<Boolean> successes;
            try {
              successes = client.bulk(pending.stream().map(Pending::item).toList());
              if (!uuid.equals(client.identity(store.head().epoch(), false)))
                throw new LogSearchClient.Unavailable();
            } catch (LogSearchClient.Unavailable failure) {
              successes = java.util.Collections.nCopies(pending.size(), false);
            }
            boolean any = false;
            for (int i = 0; i < pending.size(); i++) {
              var row = pending.get(i);
              if (successes.get(i)) {
                jdbc.update("DELETE FROM log_search_outbox WHERE event_id=?", row.item.eventId());
                any = true;
              } else
                jdbc.update(
                    "UPDATE log_search_outbox SET attempts=LEAST(attempts+1,30),next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),last_error='INDEX_RETRY' WHERE event_id=?",
                    retrySeconds(row.attempts),
                    row.item.eventId());
            }
            if (any)
              jdbc.update(
                  "UPDATE log_search_state SET last_success_at=UTC_TIMESTAMP(3) WHERE id=1");
          });
    } catch (RuntimeException error) {
      retryAfter = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      org.slf4j.LoggerFactory.getLogger(LogSearchIndexer.class)
          .warn("Search synchronization deferred");
    }
  }

  static long retrySeconds(int attempts) {
    return Math.min(60, 1L << Math.min(Math.max(0, attempts), 6));
  }

  private record Pending(LogEvent.Summary item, int attempts) {}
}
