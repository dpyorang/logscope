package com.logscope.log.controller;

import com.logscope.log.dto.LogResponses.SearchResult;
import com.logscope.log.service.LogSearchService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 검색 API의 HTTP 경계와 캐시 방지 헤더를 담당한다. */
@RestController
@RequestMapping("/api/v1/logs")
public class LogSearchController {
  private final LogSearchService service;

  /** 검색 서비스를 HTTP 경계에 연결한다. */
  public LogSearchController(LogSearchService service) {
    this.service = service;
  }

  /** GET /api/v1/logs/search: 조건에 맞는 검색 결과와 색인 대기 상태를 반환한다. */
  @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SearchResult> search(@RequestParam MultiValueMap<String, String> params) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.search(params));
  }
}
