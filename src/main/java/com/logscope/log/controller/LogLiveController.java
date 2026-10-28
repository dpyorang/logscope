package com.logscope.log.controller;

import com.logscope.log.dto.LogResponses.LiveSnapshot;
import com.logscope.log.service.LogLiveService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 최초 snapshot과 실시간 SSE 연결 API의 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/v1/logs")
public class LogLiveController {
  private final LogLiveService service;

  /** 실시간 조회 서비스를 HTTP 경계에 연결한다. */
  public LogLiveController(LogLiveService service) {
    this.service = service;
  }

  /** GET /api/v1/logs/live: 같은 DB snapshot의 최신 목록과 SSE 시작 cursor를 반환한다. */
  @GetMapping(value = "/live", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LiveSnapshot> snapshot(@RequestParam MultiValueMap<String, String> params) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.snapshot(params));
  }

  /** GET /api/v1/logs/stream: 마지막 처리 cursor부터 이어받는 SSE 연결을 연다. */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream(
      @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store, no-transform")
        .header("X-Accel-Buffering", "no")
        .body(service.connect(params, Collections.list(request.getHeaders("Last-Event-ID"))));
  }
}
