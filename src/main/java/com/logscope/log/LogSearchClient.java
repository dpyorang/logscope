package com.logscope.log;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class LogSearchClient {
  private final boolean enabled;
  private final URI base;
  private final String index;
  private final JsonMapper json =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(1))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  LogSearchClient(
      @Value("${logscope.search.enabled:false}") boolean enabled,
      @Value("${logscope.search.url:http://elasticsearch:9200}") String url,
      @Value("${logscope.search.index:logscope-logs-v1}") String index) {
    this.enabled = enabled;
    var uri = URI.create(url);
    if (!List.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !List.of("", "/").contains(uri.getPath())
        || !index.matches("[a-z0-9][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("Invalid search configuration");
    this.base = uri;
    this.index = index;
  }

  boolean enabled() {
    return enabled;
  }

  private Reply call(String method, String path, String body) {
    if (!enabled) throw new Unavailable();
    var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (body != null)
      builder.header(
          "Content-Type", path.startsWith("/_bulk") ? "application/x-ndjson" : "application/json");
    var future = http.sendAsync(builder.build(), info -> new BoundedBody());
    try {
      var response = future.get(4, TimeUnit.SECONDS);
      if (!response
          .headers()
          .firstValue("content-type")
          .orElse("")
          .toLowerCase(java.util.Locale.ROOT)
          .startsWith("application/json")) throw new Unavailable();
      var parsed = json.readTree(response.body());
      if (parsed == null || !parsed.isObject()) throw new Unavailable();
      return new Reply(response.statusCode(), parsed);
    } catch (Exception error) {
      future.cancel(true);
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new Unavailable();
    }
  }

  String identity(String epoch, boolean create) {
    var reply = call("GET", "/" + index, null);
    if (reply.status == 404 && create) {
      var created = call("PUT", "/" + index, json.writeValueAsString(mapping(epoch)));
      if (created.status != 200 && created.status != 400) throw new Unavailable();
      reply = call("GET", "/" + index, null);
    }
    if (reply.status != 200) throw new Unavailable();
    var data = reply.body.get(index);
    if (data == null) throw new Unavailable();
    var mappings = data.get("mappings");
    var meta = mappings == null ? null : mappings.get("_meta");
    if (meta == null
        || meta.path("logscopeSchema").asInt() != 1
        || !epoch.equals(meta.path("sourceEpoch").asString())) throw new Unavailable();
    var props = mappings.path("properties");
    for (String field : List.of("eventId", "serviceName", "instanceId", "level"))
      if (!"keyword".equals(props.path(field).path("type").asString())) throw new Unavailable();
    if (!"logscope_ko".equals(props.path("message").path("analyzer").asString())
        || !"date".equals(props.path("occurredAt").path("type").asString())
        || !"boolean".equals(props.path("hasException").path("type").asString()))
      throw new Unavailable();
    var uuid = data.path("settings").path("index").path("uuid");
    if (!uuid.isString() || !uuid.asString().matches("[A-Za-z0-9_-]{1,64}"))
      throw new Unavailable();
    return uuid.asString();
  }

  static Map<String, Object> mapping(String epoch) {
    var properties = new java.util.LinkedHashMap<String, Object>();
    for (String field : List.of("eventId", "serviceName", "instanceId", "level"))
      properties.put(field, Map.of("type", "keyword"));
    properties.put("occurredAt", Map.of("type", "date", "format", "strict_date_time"));
    properties.put("message", Map.of("type", "text", "analyzer", "logscope_ko"));
    properties.put("hasException", Map.of("type", "boolean"));
    return Map.of(
        "settings",
        Map.of(
            "number_of_shards",
            1,
            "number_of_replicas",
            0,
            "refresh_interval",
            "1s",
            "analysis",
            Map.of(
                "tokenizer",
                Map.of(
                    "logscope_nori", Map.of("type", "nori_tokenizer", "decompound_mode", "mixed")),
                "analyzer",
                Map.of(
                    "logscope_ko",
                    Map.of(
                        "type",
                        "custom",
                        "tokenizer",
                        "logscope_nori",
                        "filter",
                        List.of("lowercase"))))),
        "mappings",
        Map.of(
            "dynamic",
            "strict",
            "_meta",
            Map.of("logscopeSchema", 1, "sourceEpoch", epoch),
            "properties",
            properties));
  }

  List<Boolean> bulk(List<LogEvent.Summary> events) {
    var body = new StringBuilder();
    for (var event : events)
      body.append(
              json.writeValueAsString(
                  Map.of("index", Map.of("_index", index, "_id", event.eventId()))))
          .append('\n')
          .append(json.writeValueAsString(event))
          .append('\n');
    if (events.isEmpty()
        || events.size() > 50
        || body.toString().getBytes(StandardCharsets.UTF_8).length > 2097152)
      throw new IllegalArgumentException("Invalid bulk envelope");
    var reply = call("POST", "/_bulk?refresh=wait_for", body.toString());
    if (reply.status != 200
        || !reply.body.path("items").isArray()
        || reply.body.path("items").size() != events.size()) throw new Unavailable();
    var acknowledged = new ArrayList<Boolean>();
    for (int i = 0; i < events.size(); i++) {
      var item = reply.body.path("items").get(i).path("index");
      if (!events.get(i).eventId().equals(item.path("_id").asString())
          || !item.path("status").isInt()) throw new Unavailable();
      int status = item.path("status").asInt();
      acknowledged.add(status == 200 || status == 201);
    }
    return acknowledged;
  }

  List<LogEvent.Summary> search(LogSearchQuery query) {
    var reply =
        call(
            "POST",
            "/" + index + "/_search?allow_partial_search_results=false",
            json.writeValueAsString(query.body()));
    if (reply.status != 200
        || !reply.body.path("timed_out").isBoolean()
        || reply.body.path("timed_out").asBoolean()
        || reply.body.path("_shards").path("failed").asInt(-1) != 0) throw new Unavailable();
    var hits = reply.body.path("hits").path("hits");
    if (!hits.isArray() || hits.size() > query.size() + 1) throw new Unavailable();
    var result = new ArrayList<LogEvent.Summary>();
    for (var hit : hits) {
      var source = hit.path("_source");
      var values = new java.util.LinkedHashMap<String, Object>();
      for (String field :
          List.of("eventId", "occurredAt", "serviceName", "instanceId", "level", "message")) {
        if (!source.path(field).isString()) throw new Unavailable();
        values.put(field, source.path(field).asString());
      }
      if (!source.path("hasException").isBoolean()
          || !values.get("eventId").equals(hit.path("_id").asString())) throw new Unavailable();
      values.put("exception", source.path("hasException").asBoolean() ? "present" : null);
      try {
        result.add(LogInput.parse(json.valueToTree(values)).summary());
      } catch (LogApiException invalid) {
        throw new Unavailable();
      }
    }
    return result;
  }

  @jakarta.annotation.PreDestroy
  void close() {
    http.close();
  }

  private record Reply(int status, JsonNode body) {}

  static class Unavailable extends RuntimeException {
    Unavailable() {
      super("SEARCH_UNAVAILABLE");
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
      if (incoming + bytes.size() > 4194304) {
        subscription.cancel();
        result.completeExceptionally(new Unavailable());
        return;
      }
      for (var buffer : buffers) {
        var chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
