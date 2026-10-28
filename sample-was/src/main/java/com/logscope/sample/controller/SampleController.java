package com.logscope.sample.controller;

import com.logscope.sample.dto.SampleRequests.Input;
import com.logscope.sample.dto.SampleRequests.Toggle;
import com.logscope.sample.service.SampleService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 샘플 생성 화면이 사용하는 HTTP API를 담당한다. */
@RestController
public class SampleController {
  private final SampleService service;

  /** 샘플 생성 서비스를 HTTP 경계에 연결한다. */
  public SampleController(SampleService service) {
    this.service = service;
  }

  /** GET /api/status: 서버 식별자와 자동 생성 상태·주기를 반환한다. */
  @GetMapping("/api/status")
  public Map<String, Object> status() {
    return service.status();
  }

  /** GET /api/scenarios: 선택 가능한 합성 상황과 레벨을 반환한다. */
  @GetMapping("/api/scenarios")
  public List<Map<String, String>> scenarios() {
    return service.scenarios();
  }

  /** POST /api/automatic: 자동 로그 생성 여부를 바꾼다. */
  @PostMapping("/api/automatic")
  public Map<String, Object> automatic(@RequestBody Toggle input) {
    return service.automatic(input);
  }

  /** POST /api/events: 수동 합성 로그를 생성하고 잘못된 선택은 400으로 변환한다. */
  @PostMapping("/api/events")
  public Map<String, String> create(@RequestBody Input input) {
    try {
      return service.create(input);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid level or exception option");
    }
  }
}
