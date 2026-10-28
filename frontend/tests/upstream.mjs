import http from "node:http";
let mode = "normal";
let calls = [];
let streams = [];
let sequence = 0;
let history = [];
let baseline = Date.now();
let closed = 0;
const connections = new Set();
const token = () => `c${sequence}`;
function send(res, event, data, id) {
  res.write(
    `${id ? `id:${id}\n` : ""}event:${event}\ndata:${JSON.stringify(data)}\n\n`,
  );
}
function emit(value) {
  const entry = { cursor: `c${++sequence}`, item: value };
  history.push(entry);
  for (const res of connections) send(res, "log", entry, entry.cursor);
  return entry;
}
const id = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a101";
const other = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a102";
export const unused = false;
function item(eventId = id) {
  return {
    eventId,
    occurredAt: new Date(
      baseline - (mode === "expiry" ? 86400000 - 2000 : 3600000),
    ).toISOString(),
    serviceName: "sample-was",
    instanceId: eventId === id ? "was-2" : "was-1",
    level: eventId === id ? "ERROR" : "INFO",
    message:
      mode === "long"
        ? "긴 합성 한글 메시지와 경계 확인 ".repeat(100)
        : eventId === id
          ? "합성 오류 <script>window.pwned=true</script>"
          : "정상 합성 로그",
  };
}
function json(res, value, status = 200, type = "application/json") {
  res.writeHead(status, { "Content-Type": type });
  res.end(JSON.stringify(value));
}
function problem(res, status, code) {
  if (mode === "unavailable") res.setHeader("Retry-After", "3");
  json(
    res,
    {
      type: "about:blank",
      title: code,
      status,
      code,
      errors: [],
      detail: "sensitive upstream detail must not leak",
    },
    status,
    "application/problem+json",
  );
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/control") {
    if (req.method === "POST") {
      mode = url.searchParams.get("mode") || "normal";
      calls = [];
      streams = [];
      closed = 0;
      if (mode === "normal") {
        sequence = 0;
        history = [];
        baseline = Date.now();
      }
    }
    return json(res, { calls, streams, active: connections.size, closed });
  }
  if (url.pathname === "/emit") {
    let body = "";
    for await (const chunk of req) body += chunk;
    const value = JSON.parse(body);
    if (value.raw) for (const target of connections) target.write(value.raw);
    if (value.close) for (const target of connections) target.end();
    if (value.item) emit(value.item);
    if (value.items) for (const entry of value.items) emit(entry);
    return json(res, { cursor: token() });
  }
  calls.push(req.url);
  if (url.pathname === "/api/v1/logs/stream") {
    streams.push(req.url);
    if (mode === "cursor-invalid")
      return problem(res, 409, "LIVE_CURSOR_INVALID");
    if (mode === "stream-bad-cursor")
      return problem(res, 400, "VALIDATION_ERROR");
    if (mode === "capacity") return problem(res, 503, "LIVE_CAPACITY_EXCEEDED");
    if (mode === "stream-timeout") return;
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-store",
    });
    const after = url.searchParams.get("after");
    send(res, "ready", { cursor: mode === "ready-ahead" ? "c9999" : after });
    connections.add(res);
    for (const entry of history)
      if (Number(entry.cursor.slice(1)) > Number(after.slice(1)))
        send(res, "log", entry, entry.cursor);
    const heartbeat =
      mode === "stream-idle"
        ? null
        : setInterval(() => res.write(": heartbeat\n\n"), 1000);
    req.on("close", () => {
      connections.delete(res);
      clearInterval(heartbeat);
      closed++;
    });
    return;
  }
  if (["/api/v1/logs", "/api/v1/logs/live"].includes(url.pathname)) {
    if (mode === "timeout") return setTimeout(() => json(res, {}), 5500);
    if (mode === "html") {
      res.writeHead(503, { "Content-Type": "text/html" });
      return res.end("sensitive upstream detail must not leak");
    }
    if (mode === "redirect") {
      res.writeHead(302, { Location: "http://example.invalid/" });
      return res.end();
    }
    if (mode === "oversize") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.write(" ".repeat(8 * 1024 * 1024));
      return res.end(" ");
    }
    if (mode === "malformed") return json(res, { items: [] });
    if (mode === "unavailable") return problem(res, 503, "STORAGE_UNAVAILABLE");
    if (mode === "pagination")
      return problem(res, 501, "PAGINATION_NOT_IMPLEMENTED");
    if (url.pathname.endsWith("/live")) {
      const to = new Date(Date.now() + 1).toISOString();
      const items =
        mode === "empty"
          ? []
          : mode === "truncated"
            ? Array.from({ length: 50 }, (_, i) => ({
                ...item(
                  `7b2a8c30-8d94-4d5f-a6d8-${String(i).padStart(12, "0")}`,
                ),
                occurredAt: new Date(baseline - i * 1000 - 1000).toISOString(),
                hasException: false,
              }))
            : [
                { ...item(), hasException: true },
                { ...item(other), hasException: false },
              ].sort((a, b) => b.eventId.localeCompare(a.eventId));
      const value = {
        items,
        cursor: token(),
        from: new Date(Date.parse(to) - 86400000).toISOString(),
        to,
        truncated: mode === "truncated",
      };
      if (mode === "gap")
        emit({
          ...item("7b2a8c30-8d94-4d5f-a6d8-000000000111"),
          occurredAt: new Date().toISOString(),
          message: "snapshot과 연결 사이 저장",
          hasException: false,
        });
      return json(res, value);
    }
    return json(res, {
      items:
        mode === "empty"
          ? []
          : [
              { ...item(), hasException: true },
              { ...item(other), hasException: false },
            ],
      size: 50,
      nextCursor: null,
      previousCursor: null,
    });
  }
  if (url.pathname.startsWith("/api/v1/logs/")) {
    const eventId = url.pathname.split("/").at(-1);
    if (mode === "missing") return problem(res, 404, "LOG_NOT_FOUND");
    if (mode === "bad-detail") return json(res, { ...item(eventId) });
    if (mode === "wrong-id")
      return json(res, { ...item(other), exception: null });
    if (mode === "detail-delay" && eventId === id)
      return setTimeout(
        () => json(res, { ...item(), exception: "늦게 도착한 원문" }),
        700,
      );
    return json(res, {
      ...item(eventId),
      exception:
        eventId === id
          ? "java.lang.IllegalStateException: synthetic failure\n\tat com.logscope.sample.DemoService.run(DemoService.java:24)" +
            (mode === "long"
              ? "\n\tat com.logscope.sample.LongSyntheticService.execute(LongSyntheticService.java:123)".repeat(
                  100,
                )
              : "")
          : null,
    });
  }
  return problem(res, 404, "LOG_NOT_FOUND");
});
server.listen(3102, "127.0.0.1");
