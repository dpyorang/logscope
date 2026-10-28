package com.logscope.log;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
class LogStreamService {
  private final LogStore store;
  private final LogStreamSignal signal;
  private final Semaphore slots = new Semaphore(32);
  private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
  private volatile boolean stopped;
  private final java.util.function.Supplier<SseEmitter> emitters;

  @org.springframework.beans.factory.annotation.Autowired
  LogStreamService(LogStore store, LogStreamSignal signal) {
    this(store, signal, () -> new SseEmitter(60000L));
  }

  LogStreamService(
      LogStore store, LogStreamSignal signal, java.util.function.Supplier<SseEmitter> emitters) {
    this.store = store;
    this.signal = signal;
    this.emitters = emitters;
  }

  synchronized SseEmitter connect(LogStreamCursor cursor) {
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

  int activeConnections() {
    return 32 - slots.availablePermits();
  }

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

  @PreDestroy
  synchronized void shutdown() {
    stopped = true;
    workers.forEach(Thread::interrupt);
  }
}
