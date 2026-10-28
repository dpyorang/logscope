package com.logscope.log;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogSearchClientTests {
  private final LogEvent.Summary event =
      new LogEvent(
              "00000000-0000-4000-8000-000000000001",
              Instant.EPOCH,
              "sample-was",
              "was-1",
              "ERROR",
              "Synthetic",
              null)
          .summary();

  private void withReply(int status, String body, java.util.function.Consumer<LogSearchClient> test)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    var client =
        new LogSearchClient(
            true, "http://127.0.0.1:" + server.getAddress().getPort(), "logscope-logs-v1");
    try {
      test.accept(client);
    } finally {
      client.close();
      server.stop(0);
    }
  }

  @Test
  void checksEveryBulkItemEvenWhenTopLevelErrorsIsFalse() throws Exception {
    withReply(
        200,
        "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\""
            + event.eventId()
            + "\",\"status\":400}}]}",
        client -> assertThat(client.bulk(List.of(event))).containsExactly(false));
    withReply(
        200,
        "{\"items\":[{\"index\":{\"_id\":\"" + event.eventId() + "\",\"status\":201}}]}",
        client -> assertThat(client.bulk(List.of(event))).containsExactly(true));
  }

  @Test
  void malformedMismatchedAndOversizedRepliesCannotAcknowledgeWork() throws Exception {
    for (String body :
        List.of(
            "null",
            "{}",
            "{} trailing",
            "{\"items\":[]}",
            "{\"items\":[{\"index\":{\"_id\":\"other\",\"status\":201}}]}",
            "x".repeat(4194305)))
      withReply(
          200,
          body,
          client ->
              assertThatThrownBy(() -> client.bulk(List.of(event)))
                  .isInstanceOf(LogSearchClient.Unavailable.class));
    withReply(
        503,
        "{}",
        client ->
            assertThatThrownBy(() -> client.bulk(List.of(event)))
                .isInstanceOf(LogSearchClient.Unavailable.class));
  }

  @Test
  void failedShardsAndTimeoutsAreNotReportedAsSuccessfulEmptySearches() throws Exception {
    var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
    params.add("from", "2026-10-28T00:00:00Z");
    params.add("to", "2026-10-29T00:00:00Z");
    var query = LogSearchQuery.parse(params);
    for (String body :
        List.of(
            "{\"timed_out\":true,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[]}}",
            "{\"timed_out\":false,\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}"))
      withReply(
          200,
          body,
          client ->
              assertThatThrownBy(() -> client.search(query))
                  .isInstanceOf(LogSearchClient.Unavailable.class));
  }

  @Test
  void rejectsInvalidServerConfigurationAndDoesNotContactDisabledBackend() {
    assertThatThrownBy(() -> new LogSearchClient(true, "file:///tmp/search", "logs"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LogSearchClient(true, "http://localhost:9200/other", "logs"))
        .isInstanceOf(IllegalArgumentException.class);
    var disabled = new LogSearchClient(false, "http://localhost:9200", "logs");
    try {
      assertThatThrownBy(() -> disabled.bulk(List.of(event)))
          .isInstanceOf(LogSearchClient.Unavailable.class);
    } finally {
      disabled.close();
    }
  }

  @Test
  void stalledResponseBodyIsCancelledWithinWholeRequestDeadline() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 100);
          try (var out = exchange.getResponseBody()) {
            out.write('{');
            out.flush();
            Thread.sleep(6000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
    var client =
        new LogSearchClient(true, "http://127.0.0.1:" + server.getAddress().getPort(), "logs");
    try {
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(5),
          () ->
              assertThatThrownBy(() -> client.bulk(List.of(event)))
                  .isInstanceOf(LogSearchClient.Unavailable.class));
    } finally {
      client.close();
      server.stop(0);
    }
  }
}
