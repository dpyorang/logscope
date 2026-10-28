import { expect, test, type APIRequestContext } from "@playwright/test";
const upstream = "http://127.0.0.1:3102";
const mode = async (request: APIRequestContext, value: string) => {
  await request.post(`${upstream}/control?mode=${value}`);
};
const state = async (request: APIRequestContext) =>
  (await request.get(`${upstream}/control`)).json();
const searchCalls = async (request: APIRequestContext) =>
  (await state(request)).calls.filter((url: string) =>
    url.startsWith("/api/v1/logs/search?"),
  );
test.beforeEach(async ({ request }) => {
  await mode(request, "normal");
});

test("검색 진입은 SSE를 닫고 편집만으로 조회하지 않으며 서버·레벨·키워드를 함께 적용한다", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await expect.poll(async () => (await state(request)).active).toBe(0);
  await expect(
    page.getByRole("combobox", { name: "서버", exact: true }).locator("option"),
  ).toHaveText(["전체 서버", "was-1", "was-2", "was-3"]);
  await page.getByLabel("서버", { exact: true }).selectOption("was-2");
  await page.getByLabel("레벨", { exact: true }).selectOption("ERROR");
  await page
    .getByLabel("메시지 검색어", { exact: true })
    .fill("결제 오류 & + %");
  expect(await searchCalls(request)).toHaveLength(0);
  await page.getByLabel("메시지 검색어", { exact: true }).press("Enter");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  const calls = await searchCalls(request);
  expect(calls).toHaveLength(1);
  const params = new URL(calls[0], upstream).searchParams;
  expect(params.get("keyword")).toBe("결제 오류 & + %");
  expect(params.get("instanceId")).toBe("was-2");
  expect(params.get("level")).toBe("ERROR");
  expect(params.get("size")).toBe("50");
  expect(Date.parse(params.get("to")!) - Date.parse(params.get("from")!)).toBe(
    86400000,
  );
  const range = await page.locator(".query-info").textContent();
  await request.post(`${upstream}/emit`, {
    data: {
      item: {
        eventId: "7b2a8c30-8d94-4d5f-a6d8-000000000333",
        occurredAt: new Date(Date.now() - 3600000).toISOString(),
        instanceId: "was-2",
        serviceName: "sample-was",
        level: "ERROR",
        message: "검색 중 SSE로 섞이면 안 되는 로그",
        hasException: false,
      },
    },
  });
  await page.waitForTimeout(1100);
  expect(await page.locator(".query-info").textContent()).toBe(range);
  await expect(
    page.getByText("검색 중 SSE로 섞이면 안 되는 로그", { exact: true }),
  ).toHaveCount(0);
  expect(await searchCalls(request)).toHaveLength(1);
});

test("실패는 적용 조건·목록·상세를 보존하고 재시도는 편집값 대신 실패 조건을 사용한다", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page.getByLabel("레벨", { exact: true }).selectOption("ERROR");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.getByRole("button", { name: /was-2.*상세 보기/ }).click();
  await expect(page.getByLabel("Stack trace 원문")).toBeVisible();
  await mode(request, "search-unavailable");
  await page
    .getByLabel("메시지 검색어", { exact: true })
    .fill("실패한 합성 검색");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(
    page.getByText("이전 조회 결과를 표시하고 있습니다."),
  ).toBeVisible();
  const failed = (await searchCalls(request))[0];
  await expect(page.getByLabel("Stack trace 원문")).toBeVisible();
  await expect(page.locator(".applied-conditions")).toContainText(
    "메시지 전체",
  );
  await page
    .getByLabel("메시지 검색어", { exact: true })
    .fill("수정 중인 합성 검색");
  await mode(request, "search-pending");
  await page.getByRole("button", { name: "같은 조건으로 다시 시도" }).click();
  await expect(
    page.getByRole("button", { name: "닫기", exact: true }),
  ).toHaveCount(0);
  expect((await searchCalls(request))[0]).toBe(failed);
  await expect(page.getByLabel("메시지 검색어", { exact: true })).toHaveValue(
    "수정 중인 합성 검색",
  );
  await expect(page.getByText(/전체 색인 대기 3건 · 재시도 1건/)).toBeVisible();
  await expect(page.locator(".applied-conditions")).toContainText(
    "실패한 합성 검색",
  );
});

test("검색 전·빈 결과·색인 대기·오류를 구분하고 HTML을 실행하지 않는다", async ({
  page,
  request,
}, info) => {
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await expect(page.getByText("아직 성공한 검색이 없습니다")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "현재 검색 결과가 없습니다" }),
  ).toHaveCount(0);
  await mode(request, "search-empty-pending");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "현재 검색 결과가 없습니다" }),
  ).toBeVisible();
  await expect(page.getByText(/검색 반영 대기 중/)).toBeVisible();
  await mode(request, "normal");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  expect(await page.evaluate(() => "pwned" in window)).toBe(false);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: info.outputPath("search-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 360, height: 900 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: info.outputPath("search-mobile.png"),
    fullPage: true,
  });
  await page.addStyleTag({ content: "body {zoom:2}" });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("클라이언트의 기간·Unicode 길이 검증과 서버 필드 오류를 제공한다", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page
    .getByLabel("메시지 검색어", { exact: true })
    .fill("😀".repeat(201));
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(
    page.getByLabel("메시지 검색어", { exact: true }),
  ).toHaveAttribute("aria-invalid", "true");
  expect(await searchCalls(request)).toHaveLength(0);
  await page.getByLabel("메시지 검색어", { exact: true }).fill("");
  const from = await page.getByLabel("시작 시각 (UTC)").inputValue();
  await page.getByLabel("종료 시각 (UTC)").fill(from);
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.getByLabel("종료 시각 (UTC)")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  expect(await searchCalls(request)).toHaveLength(0);
  await page
    .getByRole("button", { name: "기간을 최근 24시간으로 설정" })
    .click();
  await mode(request, "search-validation");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(
    page.getByLabel("메시지 검색어", { exact: true }),
  ).toHaveAttribute("aria-invalid", "true");
  await expect(
    page.getByText("표시된 검색 조건을 확인해 주세요.", { exact: true }),
  ).toBeVisible();
});

test("늦은 검색 응답을 폐기하고 실시간 복귀는 새 snapshot을 받는다", async ({
  page,
  request,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let first = true;
  await page.route("**/api/logs/search?**", async (route) => {
    if (first) {
      first = false;
      await gate;
      await route
        .fulfill({
          json: {
            items: [],
            size: 50,
            truncated: false,
            synchronization: {
              pendingCount: 0,
              retryingCount: 0,
              indexingPending: false,
            },
          },
        })
        .catch(() => {});
    } else await route.continue();
  });
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.getByRole("button", { name: "검색 중…" })).toBeVisible();
  await page.getByRole("button", { name: "실시간 보기", exact: true }).click();
  await expect(page.getByText("실시간 연결됨", { exact: true })).toBeVisible();
  expect(
    (await state(request)).calls.filter(
      (url: string) => url === "/api/v1/logs/live",
    ),
  ).toHaveLength(2);
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  release();
  await page.waitForTimeout(150);
  await expect(page.locator("tbody tr")).toHaveCount(2);
});

test("검색 중계는 조건을 제한하고 검색 오류·잘못된 응답·전용 기한을 검사한다", async ({
  request,
}) => {
  const to = new Date().toISOString(),
    from = new Date(Date.now() - 86400000).toISOString();
  const url =
    "/api/logs/search?" + new URLSearchParams({ from, to, size: "50" });
  for (const path of [
    "/api/logs/search",
    url + "&level=bad",
    url + "&cursor=x",
    url + "&keyword=",
    url + "&size=1",
    url + "&size=101",
  ])
    expect((await request.get(path)).status()).toBe(400);
  expect((await request.post(url)).status()).toBe(405);
  for (const [value, code] of [
    ["search-unavailable", "SEARCH_UNAVAILABLE"],
    ["search-rebuilding", "SEARCH_INDEX_REBUILDING"],
    ["search-capacity", "SEARCH_CAPACITY_EXCEEDED"],
    ["search-malformed", "UPSTREAM_INVALID_RESPONSE"],
    ["search-bad-sync", "UPSTREAM_INVALID_RESPONSE"],
  ]) {
    await mode(request, value);
    const response = await request.get(url);
    expect((await response.json()).code).toBe(code);
    expect(response.headers()["cache-control"]).toBe("no-store");
    expect(await response.text()).not.toContain("sensitive upstream");
  }
  await mode(request, "search-wrong-level");
  expect((await request.get(url + "&level=ERROR")).status()).toBe(502);
  await mode(request, "search-slow");
  expect((await request.get(url)).status()).toBe(200);
  await mode(request, "search-timeout");
  const timeout = await request.get(url);
  expect(timeout.status()).toBe(504);
});

test("검색 최신 50건 제한을 안내하며 같은 조건 재조회는 초점을 빼앗지 않는다", async ({
  page,
  request,
}) => {
  await mode(request, "search-truncated");
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(50);
  await expect(
    page.getByText("최신 50건만 표시합니다.", { exact: false }),
  ).toBeVisible();
  const repeat = page.getByRole("button", { name: "적용 조건으로 다시 조회" });
  await repeat.click();
  await expect(
    page.getByRole("button", { name: "조회", exact: true }),
  ).toBeEnabled();
  await expect(repeat).toBeFocused();
});
