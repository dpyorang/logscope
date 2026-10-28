package com.logscope.log.controller;

import com.logscope.log.controller.support.LogBodyReader;
import com.logscope.log.domain.LogEvent;
import com.logscope.log.dto.LogResponses.BatchReceipt;
import com.logscope.log.dto.LogResponses.Listing;
import com.logscope.log.dto.LogResponses.Receipt;
import com.logscope.log.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 수집·정적 조회 API의 HTTP 입출력을 담당한다. */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {
  private final LogService service;
  private final LogBodyReader reader;

  /** HTTP 본문 판독기와 수집·정적 조회 서비스를 연결한다. */
  public LogController(LogService service, LogBodyReader reader) {
    this.service = service;
    this.reader = reader;
  }

  /** POST /api/v1/logs: 한 건을 수집하고 신규 저장은 201, 동일 재전송은 200을 반환한다. */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Receipt> collect(HttpServletRequest request) throws IOException {
    var receipt = service.collect(reader.read(request, 262144));
    return "STORED".equals(receipt.status())
        ? ResponseEntity.created(URI.create("/api/v1/logs/" + receipt.eventId())).body(receipt)
        : ResponseEntity.ok(receipt);
  }

  /** POST /api/v1/logs/batch: 배치를 수집하고 항목별 저장·중복·거절 결과를 반환한다. */
  @PostMapping(value = "/batch", produces = MediaType.APPLICATION_JSON_VALUE)
  public BatchReceipt collectBatch(HttpServletRequest request) throws IOException {
    return service.collectBatch(reader.read(request, 1048576));
  }

  /** GET /api/v1/logs/{eventId}: MySQL에 보존한 로그 원문을 조회한다. */
  @GetMapping(value = "/{eventId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public LogEvent.Detail detail(@PathVariable String eventId) {
    return service.detail(eventId);
  }

  /** GET /api/v1/logs: 지정한 UTC 기간의 정적 목록을 조회한다. */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public Listing list(@RequestParam MultiValueMap<String, String> params) {
    return service.list(params);
  }
}
