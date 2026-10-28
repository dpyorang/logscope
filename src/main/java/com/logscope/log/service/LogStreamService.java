package com.logscope.log.service;

import com.logscope.log.domain.LogStreamCursor;
import com.logscope.log.exception.LogApiException;
import com.logscope.log.repository.LogRepository;
import com.logscope.log.support.LogStreamSignal;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE replay·대기·종료와 제한된 연결 자원을 관리한다. */
@Service
public class LogStreamService {
  private final LogRepository store;
  private final LogStreamSignal signal;
  private final Semaphore slots = new Semaphore(32);
  private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
  private volatile boolean stopped;
  private final java.util.function.Supplier<SseEmitter> emitters;

  /** 원본·저장 신호와 실제 또는 테스트용 SSE emitter 생성기를 연결한다. */
  @org.springframework.beans.factory.annotation.Autowired
  public LogStreamService(LogRepository store, LogStreamSignal signal) {
    this(store, signal, () -> new SseEmitter(60000L));
  }

  /** 원본·저장 신호와 실제 또는 테스트용 SSE emitter 생성기를 연결한다. */
  public LogStreamService(
      LogRepository store,
      LogStreamSignal signal,
      java.util.function.Supplier<SseEmitter> emitters) {
    this.store = store;
    this.signal = signal;
    this.emitters = emitters;
  }

  /** 용량을 확보하고 응답 종료와 작업자 종료가 모두 확인될 때 연결 슬롯을 반환한다. */
  public synchronized SseEmitter connect(LogStreamCursor cursor) {
    if (stopped || !slots.tryAcquire()) throw new LogApiException(503, "LIVE_CAPACITY_EXCEEDED");
    var emitter = emitters.get();
    var closed = new AtomicBoolean();
    var workerDone = new AtomicBoolean();
    var responseDone = new AtomicBoolean();
    var released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (workerDone.get() && responseDone.get() && released.compareAndSet(false, true))
            slots.release();
        };
    Thread worker =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try {
                    pump(emitter, cursor, closed);
                  } finally {
                    workers.remove(Thread.currentThread());
                    workerDone.set(true);
                    release.run();
                  }
                });
    Runnable close =
        () -> {
          closed.set(true);
          responseDone.set(true);
          worker.interrupt();
          release.run();
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    workers.add(worker);
    worker.start();
    return emitter;
  }

  /** 응답 또는 작업자 정리가 남은 연결 수를 반환한다. */
  public int activeConnections() {
    return 32 - slots.availablePermits();
  }

  /** 저장된 로그를 replay하고 새 commit 신호·heartbeat를 기다리며 전송 한도를 지킨다. */
  private void pump(SseEmitter emitter, LogStreamCursor start, AtomicBoolean closed) {
    var cursor = start;
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(55).toNanos();
    int delivered = 0;
    try {
      emitter.send(
          SseEmitter.event()
              .name("ready")
              .reconnectTime(3000)
              .data(Map.of("cursor", cursor.toString())));
      while (!closed.get() && delivered < 128 && System.nanoTime() < deadline) {
        long observed = signal.version();
        var rows = store.after(cursor);
        for (var row : rows) {
          if (closed.get() || delivered == 128) return;
          emitter.send(SseEmitter.event().name("log").id(row.cursor()).data(row));
          cursor = LogStreamCursor.parse(row.cursor());
          delivered++;
        }
        if (rows.size() == 50) continue;
        emitter.send(SseEmitter.event().comment("heartbeat"));
        signal.await(
            observed, Math.max(1, Math.min(15000, (deadline - System.nanoTime()) / 1000000)));
      }
    } catch (org.springframework.dao.DataAccessException error) {
      try {
        emitter.send(
            SseEmitter.event().name("stream-error").data(Map.of("code", "STORAGE_UNAVAILABLE")));
      } catch (IOException | IllegalStateException ignored) {
        /* connection is already closed */
      }
    } catch (IOException | IllegalStateException error) {
      // No log payloads or transport details are logged on a broken connection.
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      emitter.complete();
    }
  }

  /** 서버 종료 시 신규 연결을 막고 전송 작업자에게 중단을 요청한다. */
  @PreDestroy
  public synchronized void shutdown() {
    stopped = true;
    workers.forEach(Thread::interrupt);
  }
}
