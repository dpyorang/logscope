package com.logscope.log.service;

import com.logscope.log.client.LogSearchClient;
import com.logscope.log.dto.LogResponses.SearchResult;
import com.logscope.log.dto.LogSearchQuery;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.repository.LogSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/** 검색 용량·색인 세대 확인과 결과 조합을 담당한다. */
@Service
public class LogSearchService {
  private final LogSearchClient client;
  private final LogSearchRepository repository;
  private final LogRepository store;
  private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(16);

  /** 검색 클라이언트와 원본·색인 상태 저장소를 연결한다. */
  public LogSearchService(
      LogSearchClient client, LogSearchRepository repository, LogRepository store) {
    this.client = client;
    this.repository = repository;
    this.store = store;
  }

  /** 동시 검색 상한과 색인 세대를 확인하고 검색 결과·전역 대기 상태를 조합한다. */
  public SearchResult search(MultiValueMap<String, String> params) {
    var query = LogSearchQuery.parse(params);
    if (!slots.tryAcquire()) throw new LogApiException(503, "SEARCH_CAPACITY_EXCEEDED");
    try {
      String uuid = client.identity(store.head().epoch(), false);
      String known = repository.indexUuid();
      if (!uuid.equals(known)) throw new LogApiException(503, "SEARCH_INDEX_REBUILDING");
      var rows = client.search(query);
      // Status is a global observation, not a count of matching documents or a search snapshot.
      if (!uuid.equals(client.identity(store.head().epoch(), false)))
        throw new LogApiException(503, "SEARCH_INDEX_REBUILDING");
      var synchronization = repository.synchronization();
      return new SearchResult(
          rows.stream().limit(query.size()).toList(),
          query.size(),
          rows.size() > query.size(),
          synchronization);

    } catch (LogSearchClient.Unavailable error) {
      throw new LogApiException(503, "SEARCH_UNAVAILABLE");
    } finally {
      slots.release();
    }
  }
}
