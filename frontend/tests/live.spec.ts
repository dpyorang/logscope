import { expect, test, type APIRequestContext } from "@playwright/test";
const control = "http://127.0.0.1:3102";
const id = (n: number) =>
  `7b2a8c30-8d94-4d5f-a6d8-${String(n).padStart(12, "0")}`;
const item = (
  n: number,
  message = `실시간 합성 로그 ${n}`,
  occurredAt = new Date(Date.now() - 1000).toISOString(),
) => ({
  eventId: id(n),
  occurredAt,
  message,
  serviceName: "sample-was",
  instanceId: "was-live",
  level: "INFO",
  hasException: false,
});
const state = async (request: APIRequestContext) =>
  (await request.get(`${control}/control`)).json();
const mode = async (request: APIRequestContext, value: string) => {
  await request.post(`${control}/control?mode=${value}`);
};
const emit = async (request: APIRequestContext, data: unknown) => {
  await request.post(`${control}/emit`, { data });
};
test.beforeEach(async ({ request }) => {
  await mode(request, "normal");
});

test("snapshot~연결 사이 저장을 이어받고 조회 시각과 실시간 수신 시각을 구분한다", async ({
  request,
  page,
}) => {
  await mode(request, "gap");
  await page.goto("/");
  await expect(
    page.getByText("snapshot과 연결 사이 저장", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await expect(
    page.getByText(/마지막 정상 실시간 로그 수신:.*UTC/),
  ).toBeVisible();
  expect(
    (await state(request)).calls.filter(
      (path: string) => path === "/api/v1/logs/live",
    ),
  ).toHaveLength(1);
  await emit(request, { item: item(12) });
  await expect(
    page.getByText("실시간 합성 로그 12", { exact: true }),
  ).toBeVisible();
});

test("정지는 연결·만료를 멈추고 재개는 저장된 cursor부터 복구하며 중복·범위 밖 로그를 처리한다", async ({
  request,
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "일시정지", exact: true }).click();
  await expect.poll(async () => (await state(request)).active).toBe(0);
  const range = await page.locator(".query-info").textContent();
  await emit(request, { item: item(21) });
  await expect(
    page.getByText("실시간 합성 로그 21", { exact: true }),
  ).toHaveCount(0);
  await page.waitForTimeout(1100);
  expect(await page.locator(".query-info").textContent()).toBe(range);
  await page.getByRole("button", { name: "실시간 재개", exact: true }).click();
  await expect(
    page.getByText("실시간 합성 로그 21", { exact: true }),
  ).toBeVisible();
  const duplicate = item(21);
  await emit(request, {
    raw: `id:c1\nevent:log\ndata:${JSON.stringify({ cursor: "c1", item: duplicate })}\n\n`,
  });
  await emit(request, {
    item: item(
      22,
      "오래된 범위 밖 로그",
      new Date(Date.now() - 3 * 86400000).toISOString(),
    ),
  });
  await emit(request, {
    item: item(
      23,
      "미래 범위 밖 로그",
      new Date(Date.now() + 3600000).toISOString(),
    ),
  });
  await expect(
    page.getByText("실시간 합성 로그 21", { exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByText("오래된 범위 밖 로그", { exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText("미래 범위 밖 로그", { exact: true }),
  ).toHaveCount(0);
  await emit(request, { close: true });
  await expect(
    page.getByText("실시간 재연결 중", { exact: true }),
  ).toBeVisible();
  await expect
    .poll(async () => (await state(request)).streams.at(-1))
    .toContain("after=c3");
});

test("새 로그 60건에도 최신 50건과 선택 상세·초점·가로 스크롤을 보존한다", async ({
  request,
  page,
}, info) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /was-2.*상세 보기/ }).click();
  const heading = page.getByRole("heading", { name: "로그 상세", exact: true });
  await expect(heading).toBeFocused();
  await page.locator(".table-scroll").evaluate((el) => {
    el.scrollLeft = 50;
  });
  const left = await page
    .locator(".table-scroll")
    .evaluate((el) => el.scrollLeft);
  const occurredAt = new Date(Date.now() - 1000).toISOString();
  await emit(request, {
    items: Array.from({ length: 60 }, (_, i) =>
      item(100 + i, `동일 시각 로그 ${i}`, occurredAt),
    ),
  });
  await expect(page.locator("tbody tr")).toHaveCount(50);
  await expect(page.locator("tbody tr").first()).toContainText(
    "동일 시각 로그 59",
  );
  await expect(page.getByLabel("Stack trace 원문")).toContainText(
    "DemoService.run",
  );
  await expect(heading).toBeFocused();
  expect(
    await page.locator(".table-scroll").evaluate((el) => el.scrollLeft),
  ).toBe(left);
  await expect(
    page.getByText("최신 50건만 표시합니다.", { exact: false }),
  ).toBeVisible();
  await page.screenshot({ path: info.outputPath("sse-desktop.png") });
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "로그 목록", exact: true }),
  ).toBeFocused();
  await page.setViewportSize({ width: 360, height: 900 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: info.outputPath("sse-mobile.png") });
});

test("정지 중 새로고침 성공은 정지를 유지하고 실패는 목록·상세·cursor를 보존한다", async ({
  request,
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await emit(request, { item: item(31) });
  await expect(
    page.getByText("실시간 합성 로그 31", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /was-2.*상세 보기/ }).click();
  await page.getByRole("button", { name: "일시정지", exact: true }).click();
  await mode(request, "unavailable");
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(
    page.getByText("이전 조회 결과를 표시하고 있습니다."),
  ).toBeVisible();
  await expect(page.getByLabel("Stack trace 원문")).toBeVisible();
  await expect(
    page.getByText("실시간 합성 로그 31", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "실시간 재개", exact: true }).click();
  await expect
    .poll(async () => (await state(request)).streams.at(-1))
    .toContain("after=c1");
  await page.getByRole("button", { name: "일시정지", exact: true }).click();
  await mode(request, "normal");
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(page.getByLabel("Stack trace 원문")).toHaveCount(0);
  await expect(
    page.getByText("실시간 일시정지", { exact: true }),
  ).toBeVisible();
  await expect.poll(async () => (await state(request)).active).toBe(0);
});

test("ready는 cursor를 앞당기지 않고 정상 EOF는 같은 위치로 재연결한다", async ({
  request,
  page,
}) => {
  await mode(request, "ready-ahead");
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await emit(request, { close: true });
  await expect(
    page.getByText("실시간 재연결 중", { exact: true }),
  ).toBeVisible();
  await expect.poll(async () => (await state(request)).streams.length).toBe(2);
  expect((await state(request)).streams.at(-1)).toContain("after=c0");
});

test("id/data 불일치와 잘못된 JSON은 처리하지 않고 마지막 정상 cursor에서 다시 받는다", async ({
  request,
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await emit(request, {
    raw: `id:c99\nevent:log\ndata:${JSON.stringify({ cursor: "c2", item: item(41, "처리하면 안 되는 로그") })}\n\n`,
  });
  await expect(
    page.getByText("실시간 연결 실패", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("처리하면 안 되는 로그", { exact: true }),
  ).toHaveCount(0);
  await emit(request, { item: item(42) });
  await expect(
    page.getByText("실시간 합성 로그 42", { exact: true }),
  ).toBeVisible();
  expect((await state(request)).streams.at(-1)).toContain("after=c0");
  await emit(request, { raw: "id:c88\nevent:log\ndata:{bad json}\n\n" });
  await expect(
    page.getByText("실시간 연결 실패", { exact: true }),
  ).toBeVisible();
  await expect
    .poll(async () => (await state(request)).streams.at(-1))
    .toContain("after=c1");
});

for (const value of ["cursor-invalid", "stream-bad-cursor"]) {
  test(`${value}: 자동 snapshot 교체 없이 기존 결과와 새 조회 안내를 유지한다`, async ({
    request,
    page,
  }) => {
    await mode(request, value);
    await page.goto("/");
    await expect(
      page.getByText("새 목록 조회 필요", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: /was-2.*상세 보기/ }),
    ).toBeVisible();
    await page.waitForTimeout(3200);
    const current = await state(request);
    expect(current.streams).toHaveLength(1);
    expect(
      current.calls.filter((path: string) => path === "/api/v1/logs/live"),
    ).toHaveLength(1);
    await mode(request, "normal");
    await page.getByRole("button", { name: "새로고침", exact: true }).click();
    await expect(
      page.getByText("실시간 연결됨", { exact: true }),
    ).toBeVisible();
  });
}

test("503과 연결 뒤 DB 오류는 기존 목록을 보존하고 다시 연결한다", async ({
  request,
  page,
}) => {
  await mode(request, "capacity");
  await page.goto("/");
  await expect(
    page.getByText("실시간 연결 실패", { exact: true }),
  ).toBeVisible();
  await mode(request, "normal");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await emit(request, {
    raw: 'event:stream-error\ndata:{"code":"STORAGE_UNAVAILABLE"}\n\n',
  });
  await expect(
    page.getByText("실시간 연결 실패", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /was-2.*상세 보기/ }),
  ).toBeVisible();
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible({
    timeout: 10_000,
  });
});

test("client idle 기한과 unmount 취소는 상류 연결을 반환한다", async ({
  request,
  page,
}) => {
  await mode(request, "stream-idle");
  await page.clock.install();
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await page.clock.runFor(35_100);
  await expect(
    page.getByText("실시간 연결 실패", { exact: true }),
  ).toBeVisible();
  await expect.poll(async () => (await state(request)).active).toBe(0);
  await page.goto("about:blank");
  await expect.poll(async () => (await state(request)).active).toBe(0);
});

test("중계의 query·시작 기한·미완성 과대 프레임과 느린 소비 취소를 검사한다", async ({
  request,
}) => {
  for (const path of [
    "/api/logs/live?x=1",
    "/api/logs/stream",
    "/api/logs/stream?after=c0&after=c1",
    "/api/logs/stream?after=c0&url=x",
  ])
    expect((await request.get(path)).status()).toBe(400);
  expect(
    (
      await request.get("/api/logs/stream?after=c0", {
        headers: { "Last-Event-ID": "bad cursor" },
      })
    ).status(),
  ).toBe(400);
  await mode(request, "stream-timeout");
  const timeout = await request.get("/api/logs/stream?after=c0");
  expect(timeout.status()).toBe(504);
  await mode(request, "normal");
  const abort = new AbortController();
  const response = await fetch(
    "http://127.0.0.1:3100/api/logs/stream?after=c0",
    { signal: abort.signal },
  );
  expect(response.headers.get("cache-control")).toBe("no-store, no-transform");
  expect(response.headers.get("x-accel-buffering")).toBe("no");
  const reader = response.body!.getReader();
  await reader.read();
  // 소비를 멈춘 상태에서도 취소는 상류 슬롯을 반환해야 한다.
  abort.abort();
  await reader.cancel().catch(() => {});
  await expect.poll(async () => (await state(request)).active).toBe(0);
  const next = await fetch("http://127.0.0.1:3100/api/logs/stream?after=c0", {
    signal: AbortSignal.timeout(5000),
  });
  const bounded = next.body!.getReader();
  await bounded.read();
  await emit(request, { raw: "data:" + "x".repeat(65537) });
  let failed = false;
  try {
    while (!(await bounded.read()).done) {
      /* 다음 조각을 검사한다. */
    }
  } catch {
    failed = true;
  }
  expect(failed).toBe(true);
  await expect.poll(async () => (await state(request)).active).toBe(0);
});

test("정지 중 경계 로그를 보존하고 재개 시 만료하며 미래 로그를 보류하지 않는다", async ({
  request,
  page,
}) => {
  await mode(request, "expiry");
  await page.clock.install();
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByRole("button", { name: "일시정지", exact: true }).click();
  await page.clock.runFor(4000);
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByRole("button", { name: "실시간 재개", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(0);
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await emit(request, {
    item: item(
      81,
      "자동 보류하지 않는 미래 로그",
      new Date(Date.now() + 10_000).toISOString(),
    ),
  });
  await expect.poll(async () => (await state(request)).active).toBe(1);
  await expect(
    page.getByText(/마지막 정상 실시간 로그 수신:.*UTC/),
  ).toBeVisible();
  await page.clock.runFor(12_000);
  await expect(
    page.getByText("자동 보류하지 않는 미래 로그", { exact: true }),
  ).toHaveCount(0);
  await emit(request, { close: true });
  await expect(
    page.getByText("실시간 재연결 중", { exact: true }),
  ).toBeVisible();
  await page.clock.runFor(3100);
  await expect
    .poll(async () => (await state(request)).streams.at(-1))
    .toContain("after=c1");
});

test("자동 삽입 때 읽던 행의 세로 위치를 지키고 사라진 행의 초점을 안전하게 옮긴다", async ({
  request,
  page,
}) => {
  await mode(request, "truncated");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  const anchor = page.locator("tbody tr").nth(20);
  const anchorId = await anchor.getAttribute("data-event-id");
  await anchor.scrollIntoViewIfNeeded();
  const top = (await anchor.boundingBox())!.y;
  await emit(request, { item: item(91) });
  const retained = page.locator(`tr[data-event-id="${anchorId}"]`);
  await expect
    .poll(async () => (await retained.boundingBox())!.y)
    .toBeCloseTo(top, 0);
  const last = page.locator("tbody tr").last().getByRole("button");
  await last.focus();
  await emit(request, { item: item(92) });
  await expect(
    page.getByRole("heading", { name: "로그 목록", exact: true }),
  ).toBeFocused();
});
