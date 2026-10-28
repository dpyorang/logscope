package com.logscope.log;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/logs")
class LogLiveController {
  private final LogStore store;
  private final LogStreamService streams;

  LogLiveController(LogStore store, LogStreamService streams) {
    this.store = store;
    this.streams = streams;
  }

  @GetMapping(value = "/live", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<LogStore.LiveSnapshot> snapshot(
      @RequestParam MultiValueMap<String, String> params) {
    if (!params.isEmpty()) throw new LogApiException("query", "UNKNOWN_FIELD");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(store.snapshot(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
  }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> stream(
      @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
    if (params.size() != 1 || !params.containsKey("after"))
      throw new LogApiException("query", "UNKNOWN_FIELD");
    if (params.get("after").size() != 1) throw new LogApiException("after", "DUPLICATE_PARAMETER");
    // Validate both sources, even when the reconnect header takes precedence.
    var query = LogStreamCursor.parse(params.getFirst("after"));
    var headers = java.util.Collections.list(request.getHeaders("Last-Event-ID"));
    if (headers.size() > 1) throw new LogApiException("after", "DUPLICATE_PARAMETER");
    var cursor = headers.isEmpty() ? query : LogStreamCursor.parse(headers.getFirst());
    cursor.validate(store.head());
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store, no-transform")
        .header("X-Accel-Buffering", "no")
        .body(streams.connect(cursor));
  }
}
