import {
  failure,
  record,
  summary,
  type Problem,
  type Range,
  type Result,
} from "./logs";
import {
  cursor,
  inRange,
  merge,
  snapshot,
  windowAt,
  type Snapshot,
} from "./live";
import { consume, START_TIMEOUT } from "./sse";

export type Phase =
  | "waiting"
  | "connecting"
  | "live"
  | "reconnecting"
  | "error"
  | "needs-refresh"
  | "paused";
export type LiveState = {
  result: Result<Snapshot>;
  range: Range;
  successAt: string | null;
  liveAt: string | null;
  phase: Phase;
  paused: boolean;
  pausedAt: string | null;
  busy: boolean;
  refreshError: Problem | null;
  streamCode: string | null;
  newCount: number;
};
export async function read<T>(
  url: string,
  valid: (value: unknown) => value is T,
  signal: AbortSignal,
): Promise<Result<T>> {
  try {
    const response = await fetch(url, {
      cache: "no-store",
      redirect: "error",
      signal,
    });
    const value: unknown = await response.json();
    if (response.ok)
      return valid(value)
        ? { ok: true, data: value }
        : failure(502, "UPSTREAM_INVALID_RESPONSE");
    return record(value) && typeof value.code === "string"
      ? {
          ok: false,
          problem: {
            status: response.status,
            code: value.code,
            errors: Array.isArray(value.errors)
              ? value.errors
                  .filter(
                    (item): item is { field: string; code: string } =>
                      record(item) &&
                      typeof item.field === "string" &&
                      typeof item.code === "string",
                  )
                  .map((item) => ({ field: item.field, code: item.code }))
              : [],
            ...(response.headers.get("retry-after")
              ? { retryAfter: response.headers.get("retry-after")! }
              : {}),
          },
        }
      : failure(502, "UPSTREAM_INVALID_RESPONSE");
  } catch {
    return signal.aborted
      ? failure(499, "REQUEST_CANCELLED")
      : failure(502, "UPSTREAM_UNAVAILABLE");
  }
}

// 연결은 한 개, 재시도 타이머도 한 개다. 결과/커서는 메모리에만 보관한다.
export class LiveStore {
  private initial: LiveState;
  private state: LiveState;
  private listeners = new Set<() => void>();
  private cursor: string | null;
  private controller: AbortController | null = null;
  private refreshController: AbortController | null = null;
  private retry: ReturnType<typeof setTimeout> | undefined;
  private expiry: ReturnType<typeof setInterval> | undefined;
  private generation = 0;
  private failures = 0;
  private active = false;
  private terminal: Phase | null = null;
  private serverTime: number;
  private clock = performance.now();
  constructor(
    initialResult: Result<Snapshot>,
    range: Range,
    successAt: string | null,
  ) {
    this.cursor = initialResult.ok ? initialResult.data.cursor : null;
    this.serverTime = Date.parse(
      initialResult.ok ? initialResult.data.to : range.to,
    );
    this.state = this.initial = {
      result: initialResult,
      range: initialResult.ok ? initialResult.data : range,
      successAt,
      liveAt: null,
      phase: initialResult.ok ? "connecting" : "waiting",
      paused: false,
      pausedAt: null,
      busy: false,
      refreshError: initialResult.ok ? null : initialResult.problem,
      streamCode: null,
      newCount: 0,
    };
  }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  getSnapshot = () => this.state;
  getServerSnapshot = () => this.initial;
  private update(patch: Partial<LiveState>) {
    this.state = { ...this.state, ...patch };
    for (const listener of this.listeners) listener();
  }
  private range() {
    return windowAt(
      this.serverTime + Math.max(0, Math.floor(performance.now() - this.clock)),
    );
  }
  private disconnect() {
    this.generation++;
    this.controller?.abort();
    this.controller = null;
    clearTimeout(this.retry);
    clearInterval(this.expiry);
  }
  start() {
    this.active = true;
    if (!this.state.paused) {
      this.tick();
      void this.connect();
    }
  }
  startFresh() {
    this.active = true;
    this.cursor = null;
    this.terminal = null;
    this.failures = 0;
    this.update({ paused: false, pausedAt: null, phase: "connecting" });
    void this.refresh();
  }
  stop() {
    this.active = false;
    this.disconnect();
    this.refreshController?.abort();
    this.refreshController = null;
    if (this.state.busy) this.update({ busy: false });
  }
  private tick() {
    clearInterval(this.expiry);
    this.expiry = setInterval(() => this.expire(), 1000);
  }
  private expire() {
    if (!this.state.result.ok || this.state.paused || this.state.busy) return;
    const range = this.range();
    this.update({
      result: { ok: true, data: merge(this.state.result.data, range) },
      range,
    });
  }
  pause = () => {
    if (this.state.busy) return;
    this.disconnect();
    this.update({ paused: true, pausedAt: this.range().to, phase: "paused" });
  };
  resume = () => {
    if (this.state.busy) return;
    this.update({
      paused: false,
      pausedAt: null,
      phase: this.terminal ?? (this.cursor ? "connecting" : "waiting"),
    });
    this.expire();
    this.tick();
    if (!this.terminal) void this.connect();
  };
  refresh = async () => {
    if (this.state.busy) return false;
    this.disconnect();
    const generation = this.generation;
    const controller = new AbortController();
    this.refreshController = controller;
    const timer = setTimeout(() => controller.abort(), 8000);
    this.update({
      busy: true,
      phase: this.state.paused ? "paused" : "connecting",
    });
    const next = await read("/api/logs/live", snapshot, controller.signal);
    clearTimeout(timer);
    if (!this.active || generation !== this.generation) return false;
    this.refreshController = null;
    if (next.ok) {
      this.cursor = next.data.cursor;
      this.terminal = null;
      this.failures = 0;
      this.serverTime = Date.parse(next.data.to);
      this.clock = performance.now();
      this.update({
        result: next,
        range: next.data,
        successAt: new Date().toISOString(),
        liveAt: null,
        refreshError: null,
        streamCode: null,
        busy: false,
        newCount: 0,
        pausedAt: this.state.paused ? next.data.to : null,
        phase: this.state.paused ? "paused" : "connecting",
      });
    } else {
      this.update({
        busy: false,
        phase: this.state.paused
          ? "paused"
          : (this.terminal ?? (this.cursor ? "reconnecting" : "waiting")),
        refreshError: {
          ...next.problem,
          code:
            next.problem.code === "REQUEST_CANCELLED"
              ? "UPSTREAM_TIMEOUT"
              : next.problem.code,
        },
      });
    }
    if (!this.state.paused) {
      this.tick();
      if (!this.terminal) void this.connect();
    }
    return next.ok;
  };
  private async connect() {
    if (!this.active || !this.cursor || this.state.paused || this.terminal)
      return;
    const generation = this.generation;
    const controller = new AbortController();
    this.controller = controller;
    let ready = false;
    const start = performance.now();
    let normal = false;
    const timer = setTimeout(() => controller.abort(), START_TIMEOUT);
    try {
      const response = await fetch(
        `/api/logs/stream?${new URLSearchParams({ after: this.cursor })}`,
        {
          signal: controller.signal,
          cache: "no-store",
          redirect: "error",
          headers: { Accept: "text/event-stream" },
        },
      );
      clearTimeout(timer);
      if (generation !== this.generation || !this.active) {
        await response.body?.cancel();
        return;
      }
      if (!response.ok) {
        const value: unknown = await response.json();
        const code =
          record(value) && typeof value.code === "string"
            ? value.code
            : "STREAM_UNAVAILABLE";
        if (response.status === 409 || response.status === 400) {
          this.terminal = "needs-refresh";
          this.update({ phase: this.terminal, streamCode: code });
          return;
        }
        throw new Error(code);
      }
      if (
        response.headers.get("content-type")?.split(";")[0] !==
        "text/event-stream"
      )
        throw new Error("STREAM_INVALID_RESPONSE");
      await consume(response, controller, (frame) => {
        if (generation !== this.generation || !this.active)
          throw new Error("STREAM_CANCELLED");
        const value: unknown = JSON.parse(frame.data);
        if (frame.event === "ready") {
          if (ready || !record(value) || !cursor(value.cursor))
            throw new Error("STREAM_INVALID_RESPONSE");
          ready = true;
          this.update({ phase: "live", streamCode: null });
        } else if (frame.event === "log") {
          if (
            !ready ||
            !record(value) ||
            !cursor(value.cursor) ||
            frame.id !== value.cursor ||
            !summary(value.item) ||
            !this.state.result.ok
          )
            throw new Error("STREAM_INVALID_RESPONSE");
          const received = value.item;
          const range = this.range();
          const isNew =
            inRange(received, range) &&
            !this.state.result.data.items.some(
              (item) => item.eventId === received.eventId,
            );
          const data = merge(this.state.result.data, range, received);
          this.update({
            result: { ok: true, data: { ...data, cursor: value.cursor } },
            range,
            liveAt: range.to,
            phase: "live",
            streamCode: null,
            newCount:
              this.state.newCount +
              (isNew &&
              data.items.some((item) => item.eventId === received.eventId)
                ? 1
                : 0),
          });
          this.cursor = value.cursor;
          this.failures = 0;
        } else if (frame.event === "stream-error") {
          throw new Error(
            record(value) && value.code === "STORAGE_UNAVAILABLE"
              ? "STORAGE_UNAVAILABLE"
              : "STREAM_INVALID_RESPONSE",
          );
        } else throw new Error("STREAM_INVALID_RESPONSE");
      });
      if (!ready) throw new Error("STREAM_INVALID_RESPONSE");
      normal = true;
      this.failures = 0;
    } catch (error) {
      if (generation !== this.generation || !this.active || this.state.paused)
        return;
      // ready 하나만 받은 짧은 실패 연결은 backoff를 초기화하지 않는다.
      if (ready && performance.now() - start >= 15_000) this.failures = 0;
      this.failures++;
      this.update({
        phase: "error",
        streamCode:
          error instanceof Error ? error.message : "STREAM_UNAVAILABLE",
      });
    } finally {
      clearTimeout(timer);
      controller.abort();
    }
    if (
      generation !== this.generation ||
      !this.active ||
      this.state.paused ||
      this.terminal
    )
      return;
    if (normal) this.update({ phase: "reconnecting", streamCode: null });
    const delay = normal
      ? 3000
      : Math.min(30_000, 3000 * 2 ** Math.min(this.failures - 1, 4));
    this.retry = setTimeout(() => {
      if (generation !== this.generation || !this.active) return;
      this.update({ phase: "reconnecting" });
      void this.connect();
    }, delay);
  }
}
