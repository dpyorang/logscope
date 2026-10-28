package com.logscope.log.service;

import com.logscope.log.domain.LogStreamCursor;
import com.logscope.log.dto.LogResponses.LiveSnapshot;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 실시간 조회 입력과 복구 시작점 검증을 담당한다. */
@Service
public class LogLiveService {
  private final LogRepository store;
  private final LogStreamService streams;

  /** snapshot 저장소와 SSE 전송 서비스를 연결한다. */
  public LogLiveService(LogRepository store, LogStreamService streams) {
    this.store = store;
    this.streams = streams;
  }

  /** 허용하지 않는 조회 인자를 거절하고 원자적인 목록·cursor snapshot을 읽는다. */
  public LiveSnapshot snapshot(MultiValueMap<String, String> params) {
    if (!params.isEmpty()) throw new LogApiException("query", "UNKNOWN_FIELD");
    return store.snapshot();
  }

  /** 쿼리와 재연결 헤더를 모두 검증한 뒤 유효한 cursor로 SSE를 시작한다. */
  public SseEmitter connect(MultiValueMap<String, String> params, List<String> headers) {
    if (params.size() != 1 || !params.containsKey("after"))
      throw new LogApiException("query", "UNKNOWN_FIELD");
    if (params.get("after").size() != 1) throw new LogApiException("after", "DUPLICATE_PARAMETER");
    // Validate both sources, even when the reconnect header takes precedence.
    var query = LogStreamCursor.parse(params.getFirst("after"));
    if (headers.size() > 1) throw new LogApiException("after", "DUPLICATE_PARAMETER");
    var cursor = headers.isEmpty() ? query : LogStreamCursor.parse(headers.getFirst());
    cursor.validate(store.head());
    return streams.connect(cursor);
  }
}
