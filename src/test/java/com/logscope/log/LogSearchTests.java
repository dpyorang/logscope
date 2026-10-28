package com.logscope.log;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class LogSearchTests {
  private LinkedMultiValueMap<String, String> params() {
    var result = new LinkedMultiValueMap<String, String>();
    result.add("from", "2026-10-28T00:00:00Z");
    result.add("to", "2026-10-29T00:00:00Z");
    return result;
  }

  @Test
  void combinesExactFiltersAndAnalyzedAndMessageWithoutInterpretingQuerySyntax() {
    var values = params();
    values.add("level", "ERROR");
    values.add("instanceId", "was-2");
    values.add("keyword", "  결제 오류 +* OR  ");
    values.add("size", "1");
    var query = LogSearchQuery.parse(values);
    assertThat(query.keyword()).isEqualTo("결제 오류 +* OR");
    var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(query.body());
    assertThat(json.path("size").asInt()).isEqualTo(2);
    assertThat(json.path("query").path("bool").path("filter").size()).isEqualTo(4);
    assertThat(
            json.path("query")
                .path("bool")
                .path("filter")
                .get(3)
                .path("match")
                .path("message")
                .path("operator")
                .asString())
        .isEqualTo("and");
    assertThat(json.toString()).doesNotContain("query_string");
    assertThat(
            json.path("query")
                .path("bool")
                .path("filter")
                .get(0)
                .path("range")
                .path("occurredAt")
                .path("lt")
                .asString())
        .isEqualTo("2026-10-29T00:00:00.000Z");
  }

  @Test
  void enforcesMandatoryUtcRangeAndTwentyFourHourBoundary() {
    assertThat(LogSearchQuery.parse(params()).size()).isEqualTo(50);
    var values = params();
    values.remove("from");
    assertThatThrownBy(() -> LogSearchQuery.parse(values)).isInstanceOf(LogApiException.class);
    for (String to :
        List.of("2026-10-28T00:00:00Z", "2026-10-29T00:00:00.001Z", "2026-10-29T00:00:00+09:00")) {
      var invalid = params();
      invalid.set("to", to);
      assertThatThrownBy(() -> LogSearchQuery.parse(invalid)).isInstanceOf(LogApiException.class);
    }
  }

  @Test
  void rejectsUnknownDuplicateBlankAndMalformedFilters() {
    for (var pair :
        List.of(
            new String[] {"level", "error"},
            new String[] {"instanceId", "was 2"},
            new String[] {"keyword", " "},
            new String[] {"keyword", "x".repeat(201)},
            new String[] {"keyword", "\ud800"},
            new String[] {"size", "0"},
            new String[] {"size", "101"},
            new String[] {"size", "-1"},
            new String[] {"size", "1.5"},
            new String[] {"cursor", "x"})) {
      var invalid = params();
      invalid.add(pair[0], pair[1]);
      assertThatThrownBy(() -> LogSearchQuery.parse(invalid)).isInstanceOf(LogApiException.class);
    }
    var duplicate = params();
    duplicate.add("from", duplicate.getFirst("from"));
    assertThatThrownBy(() -> LogSearchQuery.parse(duplicate)).isInstanceOf(LogApiException.class);
  }

  @Test
  void acceptsUnicodeCodePointLimitAndSizeBoundaries() {
    for (String size : List.of("1", "100")) {
      var values = params();
      values.add("size", size);
      values.add("keyword", "결제".repeat(100));
      assertThat(LogSearchQuery.parse(values).size()).isEqualTo(Integer.parseInt(size));
    }
    var values = params();
    values.add("keyword", "😀".repeat(200));
    assertThat(LogSearchQuery.parse(values).keyword().codePointCount(0, 400)).isEqualTo(200);
  }

  @Test
  void retryBackoffHasFloorAndCeilingWithoutOverflow() {
    assertThat(LogSearchIndexer.retrySeconds(-1)).isEqualTo(1);
    assertThat(LogSearchIndexer.retrySeconds(0)).isEqualTo(1);
    assertThat(LogSearchIndexer.retrySeconds(1)).isEqualTo(2);
    assertThat(LogSearchIndexer.retrySeconds(5)).isEqualTo(32);
    assertThat(LogSearchIndexer.retrySeconds(Integer.MAX_VALUE)).isEqualTo(60);
  }

  @Test
  void concurrentSearchLimitRejectsExcessAndReturnsSlotsAfterCompletion() throws Exception {
    var client = org.mockito.Mockito.mock(LogSearchClient.class);
    var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var store = org.mockito.Mockito.mock(LogStore.class);
    org.mockito.Mockito.when(store.head()).thenReturn(new LogStreamCursor("example", 0));
    var entered = new java.util.concurrent.CountDownLatch(16);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.when(client.identity("example", false))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return "owned";
            });
    org.mockito.Mockito.when(client.search(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    org.mockito.Mockito.when(
            jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class))
        .thenReturn("owned");
    var controller = new LogSearchController(client, jdbc, store);
    try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      try {
        for (int i = 0; i < 16; i++) futures.add(tasks.submit(() -> controller.search(params())));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> controller.search(params()))
            .hasMessage("SEARCH_CAPACITY_EXCEEDED");
      } finally {
        release.countDown();
      }
      for (var task : futures) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(controller.search(params()).getStatusCode().value()).isEqualTo(200);
    }
  }

  @Test
  void repeatedSearchFailuresDoNotLeakAdmissionSlots() {
    var client = org.mockito.Mockito.mock(LogSearchClient.class);
    var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var store = org.mockito.Mockito.mock(LogStore.class);
    org.mockito.Mockito.when(store.head()).thenReturn(new LogStreamCursor("example", 0));
    org.mockito.Mockito.when(client.identity("example", false))
        .thenThrow(new LogSearchClient.Unavailable());
    var controller = new LogSearchController(client, jdbc, store);
    for (int i = 0; i < 20; i++)
      assertThatThrownBy(() -> controller.search(params())).hasMessage("SEARCH_UNAVAILABLE");
    org.mockito.Mockito.doReturn("owned").when(client).identity("example", false);
    org.mockito.Mockito.when(
            jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class))
        .thenReturn("owned");
    org.mockito.Mockito.when(client.search(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    assertThat(controller.search(params()).getStatusCode().value()).isEqualTo(200);
  }
}
