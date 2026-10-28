import "server-only";
import { failure } from "../logs";
import { IDLE_TIMEOUT, START_TIMEOUT, SseParser } from "../sse";
import { baseUrl, boundedJson, problem } from "./log-api";
import { reply } from "./response";

export async function proxyStream(after: string, signal: AbortSignal) {
  const connection = new AbortController();
  const abort = () => connection.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  let timer = setTimeout(abort, START_TIMEOUT);
  const cleanup = () => {
    clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  };
  let origin: URL;
  try {
    origin = await baseUrl();
  } catch {
    cleanup();
    return reply(failure(503, "API_CONFIGURATION_ERROR"));
  }
  try {
    const upstream = await fetch(
      new URL(`/api/v1/logs/stream?${new URLSearchParams({ after })}`, origin),
      {
        signal: connection.signal,
        redirect: "manual",
        cache: "no-store",
        headers: { Accept: "text/event-stream, application/problem+json" },
      },
    );
    const type = upstream.headers
      .get("content-type")
      ?.split(";")[0]
      .trim()
      .toLowerCase();
    if (!upstream.ok && type === "application/problem+json") {
      const error = problem(
        await boundedJson(upstream, 65_536),
        upstream.status,
      );
      cleanup();
      connection.abort();
      return reply(
        error
          ? { ok: false, problem: error }
          : failure(502, "UPSTREAM_INVALID_RESPONSE"),
      );
    }
    if (
      upstream.status !== 200 ||
      type !== "text/event-stream" ||
      !upstream.body
    ) {
      connection.abort();
      await upstream.body?.cancel().catch(() => {});
      cleanup();
      return reply(failure(502, "UPSTREAM_INVALID_RESPONSE"));
    }
    clearTimeout(timer);
    const reader = upstream.body.getReader();
    const parser = new SseParser(() => {});
    const idle = () => {
      clearTimeout(timer);
      timer = setTimeout(abort, IDLE_TIMEOUT);
    };
    idle();
    let ended = false;
    let finishing: Promise<void> | null = null;
    const finish = () => {
      if (finishing) return finishing;
      ended = true;
      finishing = (async () => {
        cleanup();
        connection.abort();
        await reader.cancel().catch(() => {});
        reader.releaseLock();
      })();
      return finishing;
    };
    const body = new ReadableStream<Uint8Array>(
      {
        async pull(downstream) {
          try {
            const chunk = await reader.read();
            if (ended) return;
            if (chunk.done) {
              parser.finish();
              downstream.close();
              await finish();
              return;
            }
            parser.push(chunk.value);
            idle();
            downstream.enqueue(chunk.value);
          } catch {
            if (!ended) downstream.error(new Error("Log stream interrupted"));
            await finish();
          }
        },
        async cancel() {
          await finish();
        },
      },
      { highWaterMark: 0 },
    );
    return new Response(body, {
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no",
      },
    });
  } catch {
    const timedOut = connection.signal.aborted;
    cleanup();
    connection.abort();
    return reply(
      failure(
        signal.aborted ? 499 : timedOut ? 504 : 502,
        signal.aborted
          ? "REQUEST_CANCELLED"
          : timedOut
            ? "UPSTREAM_TIMEOUT"
            : "UPSTREAM_UNAVAILABLE",
      ),
    );
  }
}
