package com.logscope.log.client;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.dto.LogSearchQuery;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.validation.LogInput;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Elasticsearch의 매핑·색인·검색 문서 연산을 담당한다. */
@Component
public class LogSearchClient {
  private final boolean enabled;
  private final String index;
  private final JsonMapper json =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final LogSearchTransport transport;

  /** 서버 전용 URL·색인명을 검증하고 제한된 HTTP 통신을 준비한다. */
  public LogSearchClient(
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
    this.transport = new LogSearchTransport(enabled, uri);
    this.index = index;
  }

  /** 검색 기능의 활성화 설정을 반환한다. */
  public boolean enabled() {
    return enabled;
  }

  /** 색인의 소유 DB·매핑·UUID를 검증하고 요청된 경우에만 명시적으로 생성한다. */
  public String identity(String epoch, boolean create) {
    var reply = transport.call("GET", "/" + index, null);
    if (reply.status() == 404 && create) {
      var created = transport.call("PUT", "/" + index, json.writeValueAsString(mapping(epoch)));
      if (created.status() != 200 && created.status() != 400) throw new Unavailable();
      reply = transport.call("GET", "/" + index, null);
    }
    if (reply.status() != 200) throw new Unavailable();
    var data = reply.body().get(index);
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

  /** Nori 분석기와 엄격한 필드 형식을 사용하는 검색 색인 정의를 만든다. */
  public static Map<String, Object> mapping(String epoch) {
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

  /** eventId를 문서 ID로 보내고 refresh 확인 후 항목별 성공 여부를 반환한다. */
  public List<Boolean> bulk(List<LogEvent.Summary> events) {
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
    var reply = transport.call("POST", "/_bulk?refresh=wait_for", body.toString());
    if (reply.status() != 200
        || !reply.body().path("items").isArray()
        || reply.body().path("items").size() != events.size()) throw new Unavailable();
    var acknowledged = new ArrayList<Boolean>();
    for (int i = 0; i < events.size(); i++) {
      var item = reply.body().path("items").get(i).path("index");
      if (!events.get(i).eventId().equals(item.path("_id").asString())
          || !item.path("status").isInt()) throw new Unavailable();
      int status = item.path("status").asInt();
      acknowledged.add(status == 200 || status == 201);
    }
    return acknowledged;
  }

  /** 부분 shard 실패를 거절하고 검색 응답을 검증한 목록 읽기 모델로 변환한다. */
  public List<LogEvent.Summary> search(LogSearchQuery query) {
    var reply =
        transport.call(
            "POST",
            "/" + index + "/_search?allow_partial_search_results=false",
            json.writeValueAsString(LogSearchRequestMapper.body(query)));
    if (reply.status() != 200
        || !reply.body().path("timed_out").isBoolean()
        || reply.body().path("timed_out").asBoolean()
        || reply.body().path("_shards").path("failed").asInt(-1) != 0) throw new Unavailable();
    var hits = reply.body().path("hits").path("hits");
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

  /** 종료 시 ES HTTP 연결 자원을 해제한다. */
  @jakarta.annotation.PreDestroy
  public void close() {
    transport.close();
  }

  public static class Unavailable extends RuntimeException {
    /** ES 통신·응답 실패를 내부 세부 정보 없는 SEARCH_UNAVAILABLE로 표현한다. */
    public Unavailable() {
      super("SEARCH_UNAVAILABLE");
    }
  }
}
