package com.logscope.log;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
class LogSearchController {
  private final LogSearchClient client;
  private final JdbcTemplate jdbc;
  private final LogStore store;
  private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(16);

  LogSearchController(LogSearchClient client, JdbcTemplate jdbc, LogStore store) {
    this.client = client;
    this.jdbc = jdbc;
    this.store = store;
  }

  @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<SearchResult> search(@RequestParam MultiValueMap<String, String> params) {
    var query = LogSearchQuery.parse(params);
    if (!slots.tryAcquire()) throw new LogApiException(503, "SEARCH_CAPACITY_EXCEEDED");
    try {
      String uuid = client.identity(store.head().epoch(), false);
      String known =
          jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class);
      if (!uuid.equals(known)) throw new LogApiException(503, "SEARCH_INDEX_REBUILDING");
      var rows = client.search(query);
      // Status is a global observation, not a count of matching documents or a search snapshot.
      if (!uuid.equals(client.identity(store.head().epoch(), false)))
        throw new LogApiException(503, "SEARCH_INDEX_REBUILDING");
      var synchronization =
          jdbc.queryForObject(
              "SELECT COUNT(*) AS pending,COALESCE(SUM(attempts>0),0) AS retrying FROM log_search_outbox",
              (rs, row) ->
                  new Synchronization(
                      rs.getLong("pending"), rs.getLong("retrying"), rs.getLong("pending") > 0));
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .body(
              new SearchResult(
                  rows.stream().limit(query.size()).toList(),
                  query.size(),
                  rows.size() > query.size(),
                  synchronization));

    } catch (LogSearchClient.Unavailable error) {
      throw new LogApiException(503, "SEARCH_UNAVAILABLE");
    } finally {
      slots.release();
    }
  }

  record SearchResult(
      List<LogEvent.Summary> items, int size, boolean truncated, Synchronization synchronization) {}

  record Synchronization(long pendingCount, long retryingCount, boolean indexingPending) {}
}
