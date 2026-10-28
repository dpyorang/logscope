package com.logscope.log.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ES HTTP 통신의 기한·크기 제한과 응답 파싱을 담당한다. */
final class LogSearchTransport implements AutoCloseable {
  private final boolean enabled;
  private final URI base;
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

  /** 고정된 ES 주소와 활성 설정으로 HTTP 자원을 준비한다. */
  LogSearchTransport(boolean enabled, URI base) {
    this.enabled = enabled;
    this.base = base;
  }

  /** 리다이렉트 없이 ES에 요청하고 전체 기한·본문 크기·엄격한 JSON 형식을 검사한다. */
  Reply call(String method, String path, String body) {
    if (!enabled) throw new LogSearchClient.Unavailable();
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
          .startsWith("application/json")) throw new LogSearchClient.Unavailable();
      var parsed = json.readTree(response.body());
      if (parsed == null || !parsed.isObject()) throw new LogSearchClient.Unavailable();
      return new Reply(response.statusCode(), parsed);
    } catch (Exception error) {
      future.cancel(true);
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new LogSearchClient.Unavailable();
    }
  }

  /** 사용 중인 HTTP 클라이언트 자원을 종료한다. */
  @Override
  public void close() {
    http.close();
  }

  record Reply(int status, JsonNode body) {}

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    /** 제한된 응답 본문 수신의 완료 결과를 제공한다. */
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    /** 구독을 저장하고 한 묶음씩 본문 데이터를 요청한다. */
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    /** 누적 4MiB를 넘으면 구독을 취소하고 그렇지 않으면 다음 본문을 받는다. */
    public void onNext(List<ByteBuffer> buffers) {
      long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
      if (incoming + bytes.size() > 4194304) {
        subscription.cancel();
        result.completeExceptionally(new LogSearchClient.Unavailable());
        return;
      }
      for (var buffer : buffers) {
        var chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    /** 본문 수신 실패를 완료 결과에 전달한다. */
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    /** 검증한 크기 내에서 모은 본문 바이트를 반환한다. */
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
