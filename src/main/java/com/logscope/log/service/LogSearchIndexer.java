package com.logscope.log.service;

import com.logscope.log.client.LogSearchClient;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.repository.LogSearchRepository;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/** 검색 색인 동기화의 재구성·전송·재시도 순서를 담당한다. */
@Service
public class LogSearchIndexer {
  private final LogSearchRepository repository;
  private final LogSearchClient client;
  private final LogRepository store;
  private long retryAfter;

  /** 색인 저장소·ES 클라이언트·원본 세대 조회를 연결한다. */
  public LogSearchIndexer(
      LogSearchRepository repository, LogSearchClient client, LogRepository store) {
    this.repository = repository;
    this.client = client;
    this.store = store;
  }

  /** 색인 동기화 한 주기를 실행하고 전체 실패 시 5초 동안 다음 시도를 늦춘다. */
  public synchronized void runCycle() {
    if (!client.enabled() || System.nanoTime() < retryAfter) return;
    try {
      repository.withLockedState(this::synchronizeBatch);
    } catch (RuntimeException error) {
      retryAfter = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      org.slf4j.LoggerFactory.getLogger(LogSearchIndexer.class)
          .warn("Search synchronization deferred");
    }
  }

  /** 잠금 범위 안에서 색인 세대를 맞추고 bulk의 항목별 성공만 확인 처리한다. */
  private void synchronizeBatch(String previous) {
    String uuid = client.identity(store.head().epoch(), true);
    if (!uuid.equals(previous)) repository.rebuild(uuid);
    var pending = repository.readyBatch();
    if (pending.isEmpty()) return;
    List<Boolean> successes;
    try {
      successes = client.bulk(pending.stream().map(LogSearchRepository.Pending::item).toList());
      if (!uuid.equals(client.identity(store.head().epoch(), false)))
        throw new LogSearchClient.Unavailable();
    } catch (LogSearchClient.Unavailable failure) {
      successes = Collections.nCopies(pending.size(), false);
    }
    boolean any = false;
    for (int i = 0; i < pending.size(); i++) {
      var row = pending.get(i);
      if (successes.get(i)) {
        repository.acknowledge(row.item().eventId());
        any = true;
      } else repository.retry(row.item().eventId(), retrySeconds(row.attempts()));
    }
    if (any) repository.markSuccess();
  }

  /** 재시도 횟수를 1초부터 최대 60초까지의 지수 backoff로 바꾼다. */
  public static long retrySeconds(int attempts) {
    return Math.min(60, 1L << Math.min(Math.max(0, attempts), 6));
  }
}
