package com.logscope.log;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LogSearchIntegrationTests.Database.class)
@ActiveProfiles("test")
class LogSearchIntegrationTests {
  @Container
  static GenericContainer<?> es =
      new GenericContainer<>(
              new ImageFromDockerfile("logscope-search-tests:" + UUID.randomUUID(), true)
                  .withFileFromPath(
                      "Dockerfile", java.nio.file.Path.of("elasticsearch/Dockerfile")))
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("action.auto_create_index", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(2147483648L))
          .waitingFor(
              Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=1s")
                  .forPort(9200)
                  .forStatusCode(200))
          .withStartupTimeout(java.time.Duration.ofMinutes(3));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("logscope.search.enabled", () -> "true");
    registry.add("logscope.search.scheduling", () -> "false");
    registry.add(
        "logscope.search.url", () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Database {
    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
      return new MySQLContainer(DockerImageName.parse("mysql:8.4.13"));
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired LogSearchClient search;
  @Autowired LogStore store;
  private LogSearchIndexer indexer;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private static final String RANGE = "from=2026-10-28T00%3A00%3A00Z&to=2026-10-29T00%3A00%3A00Z";

  @BeforeEach
  void clear() throws Exception {
    jdbc.update("DELETE FROM log_events");
    jdbc.update("UPDATE log_search_state SET index_uuid=NULL,last_success_at=NULL WHERE id=1");
    elastic("DELETE", "/logscope-logs-v1", null);
    indexer = new LogSearchIndexer(jdbc, manager, search, store);
  }

  private HttpResponse<String> call(String method, String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs" + path))
            .timeout(java.time.Duration.ofSeconds(10));
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> elastic(String method, String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(
                URI.create("http://" + es.getHost() + ":" + es.getMappedPort(9200) + path))
            .timeout(java.time.Duration.ofSeconds(10));
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> event(String server, String level, String message, String time) {
    var data = new LinkedHashMap<String, Object>();
    data.put("eventId", UUID.randomUUID().toString());
    data.put("occurredAt", time);
    data.put("serviceName", "sample-was");
    data.put("instanceId", server);
    data.put("level", level);
    data.put("message", message);
    return data;
  }

  private Map<String, Object> event(String message) {
    return event("was-2", "ERROR", message, "2026-10-28T03:00:00.123Z");
  }

  private void post(Map<String, Object> data) throws Exception {
    assertThat(call("POST", "", json.writeValueAsString(data)).statusCode()).isEqualTo(201);
  }

  private JsonNode results(String extra) throws Exception {
    var response = call("GET", "/search?" + RANGE + extra, null);
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }

  @Test
  void noriMessageAndExactFiltersRespectUtcBoundariesAndNewestOrdering() throws Exception {
    var first = event("was-2", "ERROR", "결제 처리 중 오류가 발생했습니다", "2026-10-28T00:00:00.000Z");
    post(first);
    var later = event("was-2", "ERROR", "결제 오류 재시도", "2026-10-28T03:00:00.123Z");
    post(later);
    post(event("was-1", "ERROR", "결제 오류", "2026-10-28T03:00:00.123Z"));
    post(event("was-2", "INFO", "결제 오류", "2026-10-28T03:00:00.123Z"));
    post(event("was-2", "ERROR", "결제 완료", "2026-10-28T03:00:00.123Z"));
    post(event("was-2", "ERROR", "결제 오류", "2026-10-29T00:00:00.000Z"));
    indexer.runCycle();
    var result =
        results(
            "&level=ERROR&instanceId=was-2&keyword="
                + java.net.URLEncoder.encode("결제 오류", java.nio.charset.StandardCharsets.UTF_8));
    assertThat(result.path("items").size()).isEqualTo(2);
    assertThat(result.path("items").get(0).path("eventId").asString())
        .isEqualTo(later.get("eventId"));
    assertThat(result.path("items").get(1).path("eventId").asString())
        .isEqualTo(first.get("eventId"));
    assertThat(result.path("synchronization").path("indexingPending").asBoolean()).isFalse();
    var tokens =
        json.readTree(
            elastic(
                    "POST",
                    "/logscope-logs-v1/_analyze",
                    "{\"analyzer\":\"logscope_ko\",\"text\":\"결제 오류 Payment\"}")
                .body());
    var words = new java.util.ArrayList<String>();
    for (var token : tokens.path("tokens")) words.add(token.path("token").asString());
    assertThat(words).contains("결제", "오류", "payment");
  }

  @Test
  void duplicateAndReplayUseEventIdWithoutDuplicatingDocumentsOrLosingSource() throws Exception {
    var value = event("결제 오류");
    value.put("exception", "Synthetic exception detail");
    post(value);
    indexer.runCycle();
    assertThat(call("POST", "", json.writeValueAsString(value)).statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class)).isZero();
    jdbc.update("INSERT INTO log_search_outbox (event_id) VALUES (?)", value.get("eventId"));
    indexer.runCycle();
    assertThat(
            json.readTree(elastic("GET", "/logscope-logs-v1/_count", null).body())
                .path("count")
                .asInt())
        .isEqualTo(1);
    assertThat(results("").path("items").get(0).path("hasException").asBoolean()).isTrue();
    assertThat(call("GET", "/" + value.get("eventId"), null).body())
        .contains("Synthetic exception detail");
    assertThat(results("").toString()).doesNotContain("Synthetic exception detail");
    value.put("message", "Different synthetic message");
    assertThat(call("POST", "", json.writeValueAsString(value)).statusCode()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class)).isZero();
  }

  @Test
  void partialBulkFailureRetainsOnlyFailedWorkThenRecovers() throws Exception {
    indexer.runCycle();
    assertThat(
            elastic(
                    "PUT",
                    "/_ingest/pipeline/logscope-test-reject",
                    "{\"processors\":[{\"fail\":{\"if\":\"ctx.message == 'Synthetic reject'\",\"message\":\"Synthetic rejection\"}}]}")
                .statusCode())
        .isEqualTo(200);
    assertThat(
            elastic(
                    "PUT",
                    "/logscope-logs-v1/_settings",
                    "{\"index.default_pipeline\":\"logscope-test-reject\"}")
                .statusCode())
        .isEqualTo(200);
    post(event("Synthetic good"));
    post(event("Synthetic reject"));
    indexer.runCycle();
    var partial = results("");
    assertThat(partial.path("items").size()).isEqualTo(1);
    assertThat(partial.path("synchronization").path("pendingCount").asLong()).isEqualTo(1);
    assertThat(partial.path("synchronization").path("retryingCount").asLong()).isEqualTo(1);
    elastic("PUT", "/logscope-logs-v1/_settings", "{\"index.default_pipeline\":\"_none\"}");
    Thread.sleep(1100);
    indexer.runCycle();
    assertThat(results("").path("items").size()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class)).isZero();
  }

  @Test
  void outageKeepsIngestionAndDurableQueueWorkingThenNewWorkerReplays() throws Exception {
    indexer.runCycle();
    es.getDockerClient().pauseContainerCmd(es.getContainerId()).exec();
    try {
      post(event("결제 오류"));
      indexer.runCycle();
      var failed = call("GET", "/search?" + RANGE, null);
      assertThat(failed.statusCode()).isEqualTo(503);
      assertThat(failed.body()).contains("SEARCH_UNAVAILABLE");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class))
          .isEqualTo(1);
    } finally {
      es.getDockerClient().unpauseContainerCmd(es.getContainerId()).exec();
    }
    new LogSearchIndexer(jdbc, manager, search, store).runCycle();
    assertThat(results("").path("items").size()).isEqualTo(1);
  }

  @Test
  void lostIndexRebuildsFromMysqlAndForeignOwnershipIsNotOverwritten() throws Exception {
    post(event("Synthetic one"));
    post(event("Synthetic two"));
    indexer.runCycle();
    String before =
        jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class);
    elastic("DELETE", "/logscope-logs-v1", null);
    indexer.runCycle();
    assertThat(
            jdbc.queryForObject("SELECT index_uuid FROM log_search_state WHERE id=1", String.class))
        .isNotEqualTo(before);
    assertThat(results("").path("items").size()).isEqualTo(2);
    elastic(
        "PUT",
        "/logscope-logs-v1/_mapping",
        "{\"_meta\":{\"logscopeSchema\":1,\"sourceEpoch\":\"another-source\"}}");
    post(event("Synthetic pending"));
    indexer.runCycle();
    assertThat(call("GET", "/search?" + RANGE, null).statusCode()).isEqualTo(503);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class))
        .isEqualTo(1);
    assertThat(
            json.readTree(elastic("GET", "/logscope-logs-v1/_count", null).body())
                .path("count")
                .asInt())
        .isEqualTo(2);
  }

  @Test
  void boundedBatchesAndSearchTruncationExposePendingWork() throws Exception {
    for (int i = 0; i < 51; i++) post(event("Synthetic " + i));
    indexer.runCycle();
    assertThat(results("&size=1").path("synchronization").path("pendingCount").asLong())
        .isEqualTo(1);
    indexer.runCycle();
    var result = results("");
    assertThat(result.path("items").size()).isEqualTo(50);
    assertThat(result.path("truncated").asBoolean()).isTrue();
    assertThat(result.path("synchronization").path("indexingPending").asBoolean()).isFalse();
    assertThat(results("&keyword=PAYMENT_UNLIKELY_SYNTHETIC").path("items").size()).isZero();
    assertThat(call("GET", "/search?" + RANGE + "&level=INVALID", null).statusCode())
        .isEqualTo(400);
  }

  @Test
  void outboxFailureRollsBackLogAndSequenceInSameTransaction() throws Exception {
    long before =
        jdbc.queryForObject("SELECT last_sequence FROM log_stream_state WHERE id=1", Long.class);
    var value = event("Synthetic atomicity");
    jdbc.execute(
        "ALTER TABLE log_search_outbox ADD CONSTRAINT reject_search_work CHECK (event_id <> '"
            + value.get("eventId")
            + "')");
    try {
      assertThat(call("POST", "", json.writeValueAsString(value)).statusCode()).isEqualTo(503);
    } finally {
      jdbc.execute("ALTER TABLE log_search_outbox DROP CHECK reject_search_work");
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_events", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT last_sequence FROM log_stream_state WHERE id=1", Long.class))
        .isEqualTo(before);
  }

  @Test
  void concurrentWorkersSerializeConsumptionUsingDatabaseStateLock() throws Exception {
    for (int i = 0; i < 12; i++) post(event("Synthetic concurrent " + i));
    var another = new LogSearchIndexer(jdbc, manager, search, store);
    java.util.concurrent.CompletableFuture.allOf(
            java.util.concurrent.CompletableFuture.runAsync(indexer::runCycle),
            java.util.concurrent.CompletableFuture.runAsync(another::runCycle))
        .get(15, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_search_outbox", Long.class)).isZero();
    assertThat(results("").path("items").size()).isEqualTo(12);
    assertThat(
            json.readTree(elastic("GET", "/logscope-logs-v1/_count", null).body())
                .path("count")
                .asInt())
        .isEqualTo(12);
  }
}
