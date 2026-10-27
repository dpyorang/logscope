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
}
