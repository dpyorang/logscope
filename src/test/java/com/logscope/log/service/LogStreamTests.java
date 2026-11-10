package com.logscope.log.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.logscope.log.controller.LogLiveController;
import com.logscope.log.domain.LogEvent;
import com.logscope.log.domain.LogStreamCursor;
import com.logscope.log.dto.LogResponses;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.support.LogStreamSignal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

class LogStreamTests {
  private static final String EPOCH = "00000000-0000-4000-8000-000000000001";

  @Test
  void cursorAcceptsZeroAndMaximumAndRejectsMalformedAndOverflow() {
    for (long value : new long[] {0, 1, Long.MAX_VALUE}) {
      LogStreamCursor cursor = LogStreamCursor.parse(EPOCH + ":" + value);
      assertThat(cursor.sequence()).isEqualTo(value);
      assertThat(cursor.toString()).isEqualTo(EPOCH + ":" + value);
    }
    for (String value :
        new String[] {
          null,
          "",
          EPOCH,
          EPOCH + ":-1",
          EPOCH + ":01",
          EPOCH + ":1\n",
          EPOCH + ":9223372036854775808",
          "x".repeat(100)
        })
      assertThatThrownBy(() -> LogStreamCursor.parse(value)).isInstanceOf(LogApiException.class);
  }

  @Test
  void differentEpochAndFutureAreRecoveryErrors() {
    LogStreamCursor head = new LogStreamCursor(EPOCH, 10);
    new LogStreamCursor(EPOCH, 0).validate(head);
    assertThatThrownBy(() -> new LogStreamCursor(EPOCH, 11).validate(head))
        .hasMessage("LIVE_CURSOR_AHEAD");
    assertThatThrownBy(() -> new LogStreamCursor("other", 1).validate(head))
        .hasMessage("LIVE_CURSOR_INVALID");
  }

  @Test
  void headerTakesPrecedenceButBothInputsMustBeValid() {
    LogRepository store = mock(LogRepository.class);
    LogStreamService streams = mock(LogStreamService.class);
    LogLiveController controller = new LogLiveController(new LogLiveService(store, streams));
    LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<String, String>();
    params.add("after", EPOCH + ":1");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Last-Event-ID", EPOCH + ":2");
    when(store.head()).thenReturn(new LogStreamCursor(EPOCH, 3));
    controller.stream(params, request);
    verify(streams).connect(new LogStreamCursor(EPOCH, 2));
    params.set("after", "invalid");
    assertThatThrownBy(() -> controller.stream(params, request)).hasMessage("VALIDATION_ERROR");
  }

  @Test
  void unknownDuplicateAndSnapshotQueriesAreRejected() {
    LogLiveController controller =
        new LogLiveController(
            new LogLiveService(mock(LogRepository.class), mock(LogStreamService.class)));
    LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<String, String>();
    assertThatThrownBy(() -> controller.stream(params, new MockHttpServletRequest()))
        .isInstanceOf(LogApiException.class);
    params.add("after", EPOCH + ":0");
    params.add("after", EPOCH + ":0");
    assertThatThrownBy(() -> controller.stream(params, new MockHttpServletRequest()))
        .isInstanceOf(LogApiException.class);
    assertThatThrownBy(() -> controller.snapshot(params)).isInstanceOf(LogApiException.class);
  }

  @Test
  void signalChangedBetweenReadAndWaitDoesNotSleep() throws Exception {
    LogStreamSignal signal = new LogStreamSignal();
    long observed = signal.version();
    signal.changed();
    assertThat(signal.version()).isGreaterThan(observed);
    assertTimeout(java.time.Duration.ofSeconds(1), () -> signal.await(observed, 10000));
  }

  private void assertTimeout(
      java.time.Duration limit, org.junit.jupiter.api.function.Executable action) {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(limit, action);
  }

  @Test
  void streamsLimitConnectionsAndReleaseWorkersOnShutdown() throws Exception {
    LogRepository store = mock(LogRepository.class);
    when(store.after(any())).thenReturn(List.of());
    LogStreamService service = new LogStreamService(store, new LogStreamSignal(), TestEmitter::new);
    try {
      for (int i = 0; i < 32; i++) service.connect(new LogStreamCursor(EPOCH, 0));
      assertThatThrownBy(() -> service.connect(new LogStreamCursor(EPOCH, 0)))
          .hasMessage("LIVE_CAPACITY_EXCEEDED");
      assertThat(service.activeConnections()).isEqualTo(32);
    } finally {
      service.shutdown();
    }
    awaitClosed(service);
    assertThatThrownBy(() -> service.connect(new LogStreamCursor(EPOCH, 0)))
        .hasMessage("LIVE_CAPACITY_EXCEEDED");
  }

  @Test
  void storageFailureClosesInsteadOfLeakingSlot() throws Exception {
    LogRepository store = mock(LogRepository.class);
    when(store.after(any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
    LogStreamService service = new LogStreamService(store, new LogStreamSignal(), TestEmitter::new);
    service.connect(new LogStreamCursor(EPOCH, 0));
    awaitClosed(service);
    service.shutdown();
  }

  @Test
  void replayBudgetBoundsPreInitializationBuffer() throws Exception {
    LogRepository store = mock(LogRepository.class);
    LogEvent.Summary item =
        new LogEvent(
                "00000000-0000-4000-8000-000000000002",
                Instant.EPOCH,
                "sample-was",
                "was-1",
                "INFO",
                "Synthetic",
                null)
            .summary();
    when(store.after(any()))
        .thenAnswer(
            invocation -> {
              LogStreamCursor after = (LogStreamCursor) invocation.getArgument(0);
              return java.util.stream.LongStream.rangeClosed(
                      after.sequence() + 1, after.sequence() + 50)
                  .mapToObj(seq -> new LogResponses.StreamRow(EPOCH + ":" + seq, item))
                  .toList();
            });
    LogStreamService service = new LogStreamService(store, new LogStreamSignal(), TestEmitter::new);
    service.connect(new LogStreamCursor(EPOCH, 0));
    awaitClosed(service);
    verify(store, times(3)).after(any());
    service.shutdown();
  }

  @Test
  void exclusiveSnapshotEndIncludesCurrentMillisecondAcrossSecondBoundary() {
    Instant occurrence = Instant.parse("2026-10-28T00:00:00.999Z");
    Instant end = LogRepository.snapshotEnd(occurrence.plusNanos(123456));
    assertThat(occurrence).isBefore(end);
    assertThat(end).isEqualTo(Instant.parse("2026-10-28T00:00:01.000Z"));
    assertThat(LogRepository.snapshotEnd(occurrence)).isEqualTo(end);
    assertThat(java.time.Duration.between(end.minus(java.time.Duration.ofHours(24)), end))
        .isEqualTo(java.time.Duration.ofHours(24));
  }

  private void awaitClosed(LogStreamService service) throws Exception {
    long end = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
    while (service.activeConnections() != 0 && System.nanoTime() < end) Thread.sleep(10);
    assertThat(service.activeConnections()).isZero();
  }

  @Test
  void completedWorkerDoesNotReleaseSlotBeforeHttpResponseCompletes() throws Exception {
    LogRepository store = mock(LogRepository.class);
    when(store.after(any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
    TestEmitter transport =
        new TestEmitter() {
          @Override
          public void complete() {}
        };
    LogStreamService service = new LogStreamService(store, new LogStreamSignal(), () -> transport);
    service.connect(new LogStreamCursor(EPOCH, 0));
    verify(store, timeout(3000)).after(any());
    assertThat(service.activeConnections()).isEqualTo(1);
    transport.finishResponse();
    awaitClosed(service);
    service.shutdown();
  }

  // A deterministic transport substitute: production release requires both response completion and
  // worker exit.
  private static class TestEmitter
      extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
    private Runnable completion = () -> {};

    @Override
    public void onCompletion(Runnable callback) {
      completion = callback;
    }

    @Override
    public void send(SseEventBuilder event) {}

    void finishResponse() {
      completion.run();
    }

    @Override
    public void complete() {
      finishResponse();
    }
  }
}
