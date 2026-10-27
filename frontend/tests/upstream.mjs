import http from "node:http";
let mode = "normal";
let calls = [];
const id = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a101";
const other = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a102";
export const unused = false;
function item(eventId = id) {
  return {
    eventId,
    occurredAt: "2026-10-27T03:00:00.123Z",
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
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/control") {
    if (req.method === "POST") {
      mode = url.searchParams.get("mode") || "normal";
      calls = [];
    }
    return json(res, { calls });
  }
  calls.push(req.url);
  if (url.pathname === "/api/v1/logs") {
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
