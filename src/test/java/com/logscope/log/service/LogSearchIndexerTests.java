package com.logscope.log.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.logscope.log.client.LogSearchClient;
import com.logscope.log.domain.LogEvent;
import com.logscope.log.domain.LogStreamCursor;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.repository.LogSearchRepository;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LogSearchIndexerTests {
  private final LogSearchRepository repository = mock(LogSearchRepository.class);
  private final LogSearchClient client = mock(LogSearchClient.class);
  private final LogRepository store = mock(LogRepository.class);
  private final LogSearchIndexer indexer = new LogSearchIndexer(repository, client, store);

  private LogSearchRepository.Pending row(String id, int attempts) {
    return new LogSearchRepository.Pending(
        new LogEvent(
                id,
                Instant.parse("2026-10-28T00:00:00Z"),
                "sample-was",
                "was-1",
                "INFO",
                "합성 로그",
                null)
            .summary(),
        attempts);
  }

  private void locked(String previous) {
    when(client.enabled()).thenReturn(true);
    when(store.head()).thenReturn(new LogStreamCursor("synthetic-epoch", 0));
    doAnswer(
            call -> {
              Consumer<String> work = call.getArgument(0);
              work.accept(previous);
              return null;
            })
        .when(repository)
        .withLockedState(any());
    when(client.identity("synthetic-epoch", true)).thenReturn("new-index");
    when(client.identity("synthetic-epoch", false)).thenReturn("new-index");
  }

  @Test
  void acknowledgesOnlySuccessfulItemsAndRetriesFailuresInsideStateLock() {
    locked("new-index");
    var first = row("synthetic-one", 0);
    var second = row("synthetic-two", 3);
    when(repository.readyBatch()).thenReturn(List.of(first, second));
    when(client.bulk(List.of(first.item(), second.item()))).thenReturn(List.of(true, false));
    indexer.runCycle();
    var order = inOrder(repository, client);
    order.verify(repository).withLockedState(any());
    order.verify(client).identity("synthetic-epoch", true);
    order.verify(repository).readyBatch();
    order.verify(client).bulk(List.of(first.item(), second.item()));
    order.verify(client).identity("synthetic-epoch", false);
    order.verify(repository).acknowledge("synthetic-one");
    order.verify(repository).retry("synthetic-two", 8);
    order.verify(repository).markSuccess();
    verify(repository, never()).rebuild(any());
  }

  @Test
  void changedGenerationRebuildsBeforeReadingAndDoesNotAcknowledgeFailedBulk() {
    locked("old-index");
    var pending = row("synthetic-one", 0);
    when(repository.readyBatch()).thenReturn(List.of(pending));
    when(client.bulk(any())).thenThrow(new LogSearchClient.Unavailable());
    indexer.runCycle();
    var order = inOrder(repository);
    order.verify(repository).withLockedState(any());
    order.verify(repository).rebuild("new-index");
    order.verify(repository).readyBatch();
    order.verify(repository).retry("synthetic-one", 1);
    verify(repository, never()).acknowledge(any());
    verify(repository, never()).markSuccess();
  }

  @Test
  void disabledSearchAndWholeCycleFailureRetainWorkAndBackOff() {
    indexer.runCycle();
    verifyNoInteractions(repository, store);
    when(client.enabled()).thenReturn(true);
    doThrow(new IllegalStateException("synthetic unavailable"))
        .when(repository)
        .withLockedState(any());
    indexer.runCycle();
    indexer.runCycle();
    verify(repository).withLockedState(any());
    verify(repository, never()).acknowledge(any());
  }
}
