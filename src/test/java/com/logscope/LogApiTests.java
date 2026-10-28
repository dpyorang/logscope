package com.logscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogApiTests {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private static final String RANGE = "?from=2026-10-27T00:00:00Z&to=2026-10-28T00:00:00Z";

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM log_events");
  }

  private Map<String, Object> event(String id) {
    var result = new LinkedHashMap<String, Object>();
    result.put("eventId", id);
    result.put("occurredAt", "2026-10-27T03:00:00.123Z");
    result.put("serviceName", "sample-was");
    result.put("instanceId", "was-2");
    result.put("level", "ERROR");
    result.put("message", "Synthetic failure for logscope demo");
    result.put(
        "exception",
        "java.lang.IllegalStateException: synthetic failure\n\tat com.logscope.sample.DemoService.run(DemoService.java:24)");
    return result;
  }

  private HttpResponse<String> send(String method, String path, String body, String contentType)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs" + path));
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", contentType)
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(Map<String, Object> body) throws Exception {
    return send("POST", "", json.writeValueAsString(body), "application/json");
  }

  private JsonNode tree(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private void problem(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("content-type").orElse(""))
        .contains("application/problem+json");
    assertThat(tree(response).get("code").asString()).isEqualTo(code);
  }

  @Test
  void batchStoresValidItemsAndRejectsPoisonWithoutLosingOthers() throws Exception {
    var good = event(UUID.randomUUID().toString());
    var invalid = event(UUID.randomUUID().toString());
    invalid.put("level", "INVALID");
    var conflict = new LinkedHashMap<>(good);
    conflict.put("message", "Different synthetic value");
    var body = json.writeValueAsString(java.util.List.of(good, good, invalid, conflict));
    var result = send("POST", "/batch", body, "application/json");
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(tree(result).get("stored").asInt()).isEqualTo(1);
    assertThat(tree(result).get("duplicates").asInt()).isEqualTo(1);
    assertThat(tree(result).get("rejected").size()).isEqualTo(2);
    assertThat(tree(result).get("rejected").get(0).get("index").asInt()).isEqualTo(2);
    assertThat(tree(result).get("rejected").get(1).get("code").asString())
        .isEqualTo("EVENT_ID_CONFLICT");
    assertThat(tree(send("GET", "/" + good.get("eventId"), null, "")))
        .isEqualTo(json.valueToTree(good));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Integer.class)).isEqualTo(1);
  }

  @Test
  void batchEnforcesEnvelopeCountAndByteLimits() throws Exception {
    for (String body : java.util.List.of("[]", "{}", "[" + "{},".repeat(100) + "{}]"))
      problem(send("POST", "/batch", body, "application/json"), 400, "VALIDATION_ERROR");
    var hundred =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> event(UUID.randomUUID().toString()))
            .toList();
    assertThat(
            tree(send("POST", "/batch", json.writeValueAsString(hundred), "application/json"))
                .get("stored")
                .asInt())
        .isEqualTo(100);
    problem(
        send("POST", "/batch", " ".repeat(1048577), "application/json"), 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void storesAndReadsSyntheticLogThroughRealHttpAndMysql() throws Exception {
    String id = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a101";
    var input = event(id);
    var saved = post(input);
    assertThat(saved.statusCode()).isEqualTo(201);
    assertThat(saved.headers().firstValue("location")).hasValue("/api/v1/logs/" + id);
    assertThat(tree(saved).get("status").asString()).isEqualTo("STORED");
    var detail = send("GET", "/" + id, null, "");
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(tree(detail)).isEqualTo(json.valueToTree(input));
    var listing = send("GET", RANGE, null, "");
    assertThat(listing.statusCode()).isEqualTo(200);
    assertThat(tree(listing).get("items").size()).isEqualTo(1);
    assertThat(tree(listing).get("items").get(0).get("hasException").asBoolean()).isTrue();
    assertThat(tree(listing).get("items").get(0).has("exception")).isFalse();
    assertThat(tree(listing).get("nextCursor").isNull()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Integer.class)).isEqualTo(1);
  }

  @Test
  void normalizesMillisecondsAndAbsentExceptionForIdempotency() throws Exception {
    String id = UUID.randomUUID().toString();
    var input = event(id);
    input.put("occurredAt", "2026-10-27T03:00:00Z");
    input.remove("exception");
    assertThat(post(input).statusCode()).isEqualTo(201);
    input.put("occurredAt", "2026-10-27T03:00:00.000Z");
    input.put("exception", null);
    var duplicate = post(input);
    assertThat(duplicate.statusCode()).isEqualTo(200);
    assertThat(tree(duplicate).get("status").asString()).isEqualTo("DUPLICATE");
    var detail = tree(send("GET", "/" + id, null, ""));
    assertThat(detail.get("occurredAt").asString()).isEqualTo("2026-10-27T03:00:00.000Z");
    assertThat(detail.get("exception").isNull()).isTrue();
  }

  @Test
  void conflictDoesNotOverwriteOriginal() throws Exception {
    String id = UUID.randomUUID().toString();
    var input = event(id);
    post(input);
    input.put("message", "Different synthetic message");
    problem(post(input), 409, "EVENT_ID_CONFLICT");
    assertThat(tree(send("GET", "/" + id, null, "")).get("message").asString())
        .isEqualTo("Synthetic failure for logscope demo");
  }

  @Test
  void concurrentIdenticalRequestsCreateExactlyOneRow() throws Exception {
    var input = event(UUID.randomUUID().toString());
    var tasks = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 8; i++) {
      tasks.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(input);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    }
    var responses = tasks.stream().map(CompletableFuture::join).toList();
    assertThat(responses.stream().filter(r -> r.statusCode() == 201).count()).isEqualTo(1);
    assertThat(responses.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(7);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Integer.class)).isEqualTo(1);
  }

  @Test
  void validatesTypesUnknownFieldsDatesAndRequiredValuesWithoutReflectingInputs() throws Exception {
    for (Map.Entry<String, Object> invalid :
        Map.<String, Object>of(
                "eventId",
                "INVALID",
                "occurredAt",
                "2026-02-30T03:00:00Z",
                "serviceName",
                "Bad Name",
                "instanceId",
                123,
                "level",
                "FATAL",
                "message",
                " ",
                "exception",
                false,
                "extra",
                "synthetic")
            .entrySet()) {
      var input = event(UUID.randomUUID().toString());
      input.put(invalid.getKey(), invalid.getValue());
      problem(post(input), 400, "VALIDATION_ERROR");
    }
    for (String time :
        new String[] {
          "2026-10-27T03:00:00+00:00",
          "2026-10-27T03:00:00.1234Z",
          "2026-10-27T03:00:60Z",
          "0999-01-01T00:00:00Z"
        }) {
      var input = event(UUID.randomUUID().toString());
      input.put("occurredAt", time);
      problem(post(input), 400, "VALIDATION_ERROR");
    }
    var input = event(UUID.randomUUID().toString());
    input.remove("message");
    problem(post(input), 400, "VALIDATION_ERROR");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Integer.class)).isZero();
  }

  @Test
  void preservesMaximumUnicodeAndTraceWithoutTruncation() throws Exception {
    String id = UUID.randomUUID().toString();
    var input = event(id);
    input.put("message", "😀".repeat(4096));
    input.put("exception", "😀".repeat(32768));
    assertThat(post(input).statusCode()).isEqualTo(201);
    assertThat(tree(send("GET", "/" + id, null, ""))).isEqualTo(json.valueToTree(input));
    input.put("message", "😀".repeat(4097));
    problem(post(input), 400, "VALIDATION_ERROR");
    input.put("message", "normal");
    input.put("exception", "a".repeat(32769));
    problem(post(input), 400, "VALIDATION_ERROR");
  }

  @Test
  void enforcesActualBodyLimitEvenWithoutContentLength() throws Exception {
    var input = event(UUID.randomUUID().toString());
    input.put("message", "a".repeat(262144));
    byte[] body = json.writeValueAsBytes(input);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(body)))
            .build();
    problem(client.send(request, HttpResponse.BodyHandlers.ofString()), 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void acceptsExactByteLimitAndRejectsOneByteMore() throws Exception {
    String raw = json.writeValueAsString(event(UUID.randomUUID().toString()));
    String exact =
        raw + " ".repeat(262144 - raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    assertThat(send("POST", "", exact, "application/json").statusCode()).isEqualTo(201);
    problem(send("POST", "", exact + " ", "application/json"), 413, "PAYLOAD_TOO_LARGE");
    var invalidUtf8 =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, (byte) 0x28}))
            .build();
    problem(client.send(invalidUtf8, HttpResponse.BodyHandlers.ofString()), 400, "MALFORMED_JSON");
  }

  @Test
  void rejectsMalformedJsonDuplicateKeysAndUnsupportedMedia() throws Exception {
    problem(send("POST", "", "{", "application/json"), 400, "MALFORMED_JSON");
    problem(send("POST", "", "{} {}", "application/json"), 400, "MALFORMED_JSON");
    problem(
        send("POST", "", "{\"eventId\":\"a\",\"eventId\":\"b\"}", "application/json"),
        400,
        "MALFORMED_JSON");
    problem(send("POST", "", "{}", "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE");
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    problem(
        client.send(request, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void distinguishesMissingAndInvalidDetailIds() throws Exception {
    problem(send("GET", "/" + UUID.randomUUID(), null, ""), 404, "LOG_NOT_FOUND");
    problem(send("GET", "/invalid", null, ""), 400, "VALIDATION_ERROR");
  }

  @Test
  void enforcesRequiredTimeRangeAndQueryRules() throws Exception {
    for (String query :
        new String[] {
          "",
          "?from=2026-10-27T00:00:00Z",
          "?from=2026-10-27T00:00:00Z&to=2026-10-27T00:00:00Z",
          "?from=2026-10-27T00:00:00Z&to=2026-10-28T00:00:00.001Z",
          RANGE + "&size=0",
          RANGE + "&size=101",
          RANGE + "&size=abc",
          RANGE + "&size=1&size=2",
          RANGE + "&unknown=x"
        }) {
      problem(send("GET", query, null, ""), 400, "VALIDATION_ERROR");
    }
    assertThat(send("GET", RANGE, null, "").statusCode()).isEqualTo(200);
    problem(send("GET", RANGE + "&level=ERROR", null, ""), 501, "QUERY_FEATURE_NOT_IMPLEMENTED");
  }

  @Test
  void timeBoundsAreHalfOpenAndOrderingIsStable() throws Exception {
    var first = event("00000000-0000-4000-8000-000000000001");
    first.put("occurredAt", "2026-10-27T00:00:00Z");
    post(first);
    var second = event("00000000-0000-4000-8000-000000000002");
    second.put("occurredAt", "2026-10-27T00:00:00Z");
    post(second);
    var excluded = event(UUID.randomUUID().toString());
    excluded.put("occurredAt", "2026-10-28T00:00:00Z");
    post(excluded);
    var items = tree(send("GET", RANGE, null, "")).get("items");
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).get("eventId").asString()).isEqualTo(second.get("eventId"));
    problem(send("GET", RANGE + "&size=1", null, ""), 501, "PAGINATION_NOT_IMPLEMENTED");
  }

  private Map<String, Object> liveEvent() {
    var body = event(UUID.randomUUID().toString());
    body.put(
        "occurredAt",
        java.time.Instant.now()
            .minusSeconds(30)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .toString());
    return body;
  }

  private record Frame(String event, String id, String data) {}

  private Frame frame(java.io.BufferedReader reader) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                String type = "", id = "";
                var data = new StringBuilder();
                while (true) {
                  String line = reader.readLine();
                  if (line == null)
                    throw new IllegalStateException("SSE closed before expected frame");
                  if (line.isEmpty()) {
                    if (!type.isEmpty()) return new Frame(type, id, data.toString());
                    continue;
                  }
                  if (line.startsWith("event:")) type = line.substring(6).stripLeading();
                  if (line.startsWith("id:")) id = line.substring(3).stripLeading();
                  if (line.startsWith("data:")) data.append(line.substring(5).stripLeading());
                }
              } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
              }
            })
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  private HttpResponse<java.io.InputStream> connect(String cursor, String lastId) throws Exception {
    var builder =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/v1/logs/stream?after=" + cursor));
    if (lastId != null) builder.header("Last-Event-ID", lastId);
    return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  @Test
  void snapshotConnectionGapLiveDeliveryReconnectAndDuplicateAreSafe() throws Exception {
    var first = liveEvent();
    assertThat(post(first).statusCode()).isEqualTo(201);
    var snapshot = tree(send("GET", "/live", null, ""));
    String start = snapshot.get("cursor").asString();
    assertThat(snapshot.get("items").size()).isEqualTo(1);
    var late = liveEvent();
    late.put("occurredAt", "2026-01-01T00:00:00Z");
    assertThat(post(late).statusCode()).isEqualTo(201);
    assertThat(post(late).statusCode()).isEqualTo(200);
    String received;
    var response = connect(start, null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type").orElseThrow())
        .contains("text/event-stream");
    assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
    try (var input = response.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      assertThat(frame(reader).event()).isEqualTo("ready");
      var replay = frame(reader);
      received = replay.id();
      assertThat(json.readTree(replay.data()).get("item").get("eventId").asString())
          .isEqualTo(late.get("eventId"));
      var live = liveEvent();
      post(live);
      var pushed = frame(reader);
      assertThat(json.readTree(pushed.data()).get("item").get("eventId").asString())
          .isEqualTo(live.get("eventId"));
      received = pushed.id();
    }
    // Pause closes the stream. Resume with the last processed ID, after a new stored event.
    var paused = liveEvent();
    post(paused);
    var resumed = connect(start, received);
    try (var input = resumed.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      assertThat(json.readTree(frame(reader).data()).get("cursor").asString()).isEqualTo(received);
      var pushed = frame(reader);
      assertThat(json.readTree(pushed.data()).get("item").get("eventId").asString())
          .isEqualTo(paused.get("eventId"));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Integer.class)).isEqualTo(4);
  }

  @Test
  void liveSnapshotIsBoundedAndReplayContinuesAcrossConnectionBudget() throws Exception {
    var empty = tree(send("GET", "/live", null, ""));
    String start = empty.get("cursor").asString();
    assertThat(empty.get("from").asString()).matches(".*\\.\\d{3}Z");
    var events = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 129; i++) events.add(liveEvent());
    for (int i = 0; i < events.size(); i += 100)
      assertThat(
              send(
                      "POST",
                      "/batch",
                      json.writeValueAsString(events.subList(i, Math.min(i + 100, events.size()))),
                      "application/json")
                  .statusCode())
          .isEqualTo(200);
    var snapshot = tree(send("GET", "/live", null, ""));
    assertThat(snapshot.get("items").size()).isEqualTo(50);
    assertThat(snapshot.get("truncated").asBoolean()).isTrue();
    var response = connect(start, null);
    String received = start;
    var ids = new java.util.HashSet<String>();
    try (var input = response.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      frame(reader);
      for (int i = 0; i < 128; i++) {
        var next = frame(reader);
        received = next.id();
        assertThat(next.event()).isEqualTo("log");
        assertThat(ids.add(json.readTree(next.data()).get("item").get("eventId").asString()))
            .isTrue();
      }
      assertThat(reader.readLine()).isNull();
    }
    var resumed = connect(received, null);
    try (var input = resumed.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      frame(reader);
      var next = frame(reader);
      assertThat(ids.add(json.readTree(next.data()).get("item").get("eventId").asString()))
          .isTrue();
    }
    assertThat(ids).hasSize(129);
  }

  @Test
  void liveRejectsBadCursorsAndUnknownParametersBeforeStartingStream() throws Exception {
    String head = tree(send("GET", "/live", null, "")).get("cursor").asString();
    String epoch = head.substring(0, head.indexOf(':'));
    problem(send("GET", "/stream?after=invalid", null, ""), 400, "VALIDATION_ERROR");
    problem(
        send("GET", "/stream?after=" + head + "&after=" + head, null, ""), 400, "VALIDATION_ERROR");
    problem(send("GET", "/live?from=x", null, ""), 400, "VALIDATION_ERROR");
    problem(
        send("GET", "/stream?after=" + epoch + ":9223372036854775807", null, ""),
        409,
        "LIVE_CURSOR_AHEAD");
    problem(
        send("GET", "/stream?after=00000000-0000-4000-8000-000000000000:0", null, ""),
        409,
        "LIVE_CURSOR_INVALID");
  }

  @Test
  void concurrentCommitsHaveStableReplayOrderAndConflictsDoNotAdvanceHead() throws Exception {
    String start = tree(send("GET", "/live", null, "")).get("cursor").asString();
    var tasks = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 8; i++) {
      var value = liveEvent();
      tasks.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(value);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    }
    for (var task : tasks) assertThat(task.get().statusCode()).isEqualTo(201);
    var sequences =
        jdbc.queryForList(
            "SELECT stream_sequence FROM log_events ORDER BY stream_sequence", Long.class);
    long base = Long.parseLong(start.substring(start.indexOf(':') + 1));
    assertThat(sequences)
        .containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(base + 1, base + 8).boxed().toList());
    var id =
        jdbc.queryForObject(
            "SELECT event_id FROM log_events ORDER BY stream_sequence LIMIT 1", String.class);
    var conflict = liveEvent();
    conflict.put("eventId", id);
    problem(post(conflict), 409, "EVENT_ID_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_sequence FROM log_stream_state WHERE id=1", Long.class))
        .isEqualTo(base + 8);
  }

  @Test
  void pendingCommitCannotEscapeSnapshotThenAppearBehindCursor() throws Exception {
    String before = tree(send("GET", "/live", null, "")).get("cursor").asString();
    var value = liveEvent();
    CompletableFuture<HttpResponse<String>> writer;
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var lock = connection.createStatement();
          var ignored =
              lock.executeQuery(
                  "SELECT last_sequence FROM log_stream_state WHERE id=1 FOR UPDATE")) {
        writer =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return post(value);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
        var snapshot = tree(send("GET", "/live", null, ""));
        assertThat(snapshot.get("cursor").asString()).isEqualTo(before);
        assertThat(snapshot.get("items").size()).isZero();
        connection.commit();
      }
    }
    assertThat(writer.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    var response = connect(before, null);
    try (var input = response.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      frame(reader);
      var next = frame(reader);
      assertThat(json.readTree(next.data()).get("item").get("eventId").asString())
          .isEqualTo(value.get("eventId"));
    }
  }

  @Test
  void liveSnapshotExcludesExpiredAndFutureButReplayAdvancesAcrossBoth() throws Exception {
    String start = tree(send("GET", "/live", null, "")).get("cursor").asString();
    var expired = liveEvent();
    expired.put(
        "occurredAt",
        java.time.Instant.now()
            .minusSeconds(86460)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .toString());
    var future = liveEvent();
    future.put(
        "occurredAt",
        java.time.Instant.now()
            .plusSeconds(60)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .toString());
    post(expired);
    post(future);
    assertThat(tree(send("GET", "/live", null, "")).get("items").size()).isZero();
    var response = connect(start, null);
    try (var input = response.body();
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
      frame(reader);
      assertThat(json.readTree(frame(reader).data()).get("item").get("eventId").asString())
          .isEqualTo(expired.get("eventId"));
      assertThat(json.readTree(frame(reader).data()).get("item").get("eventId").asString())
          .isEqualTo(future.get("eventId"));
    }
  }
}
