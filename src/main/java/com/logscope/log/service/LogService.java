package com.logscope.log.service;

import com.logscope.log.domain.LogEvent;
import com.logscope.log.dto.LogListQuery;
import com.logscope.log.dto.LogResponses.BatchReceipt;
import com.logscope.log.dto.LogResponses.Listing;
import com.logscope.log.dto.LogResponses.Receipt;
import com.logscope.log.dto.LogResponses.Rejected;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.validation.LogInput;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 단건·배치 수집과 정적 조회의 처리 흐름을 담당한다. */
@Service
public class LogService {
  private final LogRepository store;
  private final JsonMapper parser = JsonMapper.builder().build();

  /** 단건·배치 수집과 정적 조회에 사용할 원본 저장소를 연결한다. */
  public LogService(LogRepository store) {
    this.store = store;
  }

  /** 로그 입력을 검증하고 원본 저장 결과를 수집 영수증으로 변환한다. */
  public Receipt collect(JsonNode body) {
    var event = LogInput.parse(body);
    return new Receipt(event.eventId(), store.insert(event) ? "STORED" : "DUPLICATE");
  }

  /** 최대 100건을 개별 처리하며 입력 거절은 모으고 저장소 장애는 호출자에게 전파한다. */
  public BatchReceipt collectBatch(JsonNode body) {
    if (body == null || !body.isArray() || body.isEmpty() || body.size() > 100)
      throw new LogApiException("body", "OUT_OF_RANGE");
    int stored = 0;
    int duplicates = 0;
    var rejected = new java.util.ArrayList<Rejected>();
    for (int i = 0; i < body.size(); i++) {
      try {
        if (parser.writeValueAsBytes(body.get(i)).length > 262144)
          throw new LogApiException(413, "PAYLOAD_TOO_LARGE");
        if (store.insert(LogInput.parse(body.get(i)))) stored++;
        else duplicates++;
      } catch (LogApiException error) {
        rejected.add(new Rejected(i, error.code(), error.errors()));
      }
    }
    if (!rejected.isEmpty())
      org.slf4j.LoggerFactory.getLogger(LogService.class)
          .warn("Batch rejected {} items", rejected.size());
    return new BatchReceipt(stored, duplicates, rejected);
  }

  /** eventId를 검증하고 저장 원문의 상세 표현을 만든다. */
  public LogEvent.Detail detail(String eventId) {
    return store.find(LogInput.uuid(eventId)).detail();
  }

  /** 기간·크기·미구현 조건을 검증하고 정적 목록을 만든다. */
  public Listing list(MultiValueMap<String, String> params) {
    var query = LogListQuery.parse(params);
    var rows = store.list(query.from(), query.to(), query.size());
    if (rows.size() > query.size()) throw new LogApiException(501, "PAGINATION_NOT_IMPLEMENTED");
    return new Listing(rows.stream().map(LogEvent::summary).toList(), query.size(), null, null);
  }
}
