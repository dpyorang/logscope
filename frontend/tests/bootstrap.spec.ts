import { expect, test } from "@playwright/test";
const id = "7b2a8c30-8d94-4d5f-a6d8-931cf6a6a101";
async function mode(
  request: import("@playwright/test").APIRequestContext,
  value: string,
) {
  await request.post(`http://127.0.0.1:3102/control?mode=${value}`);
}
test.beforeEach(async ({ request }) => {
  await mode(request, "normal");
});

test("JavaScript 없이 SSR 목록을 제공하고 브라우저 준비만으로 중복 조회하지 않는다", async ({
  browser,
  request,
  page,
}) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const raw = await context.newPage();
  const response = await raw.goto("http://127.0.0.1:3100/");
  expect(response?.headers()["cache-control"]).toContain("no-store");
  await expect(raw.getByText("was-2", { exact: true })).toBeVisible();
  await expect(raw.locator("html")).toHaveAttribute("lang", "ko");
  await context.close();
  await mode(request, "normal");
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: /was-2.*상세 보기/ }),
  ).toBeVisible();
  const state = await (
    await request.get("http://127.0.0.1:3102/control")
  ).json();
  expect(state.calls).toHaveLength(1);
  const html = await (await request.get("/")).text();
  expect(html).not.toContain("127.0.0.1:3102");
  expect(await page.evaluate(() => "pwned" in window)).toBe(false);
});
test("키보드 상세 열기·원문·복사·닫기와 모바일 배치를 제공한다", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "본문으로 바로가기" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  const button = page.getByRole("button", { name: /was-2.*상세 보기/ });
  await button.focus();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("heading", { name: "로그 상세", exact: true }),
  ).toBeFocused();
  await expect(page.getByLabel("Stack trace 원문")).toContainText(
    "DemoService.run",
  );
  await page.getByRole("button", { name: "원문 복사" }).click();
  await expect(page.getByText("Stack trace를 복사했습니다.")).toBeVisible();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(button).toBeFocused();
  await page.setViewportSize({ width: 360, height: 800 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: /was-1.*상세 보기/ }).click();
  await expect(
    page.getByText("이 로그에는 stack trace가 없습니다."),
  ).toBeVisible();
});
test("초기 0건과 실패를 구분하고 실패 재시도는 같은 절대 범위를 사용한다", async ({
  request,
  page,
}) => {
  await mode(request, "empty");
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "조회 범위에 로그가 없습니다" }),
  ).toBeVisible();
  await mode(request, "unavailable");
  await page.goto("/");
  await expect(page.getByText("아직 성공한 조회가 없습니다")).toBeVisible();
  const before = await (
    await request.get("http://127.0.0.1:3102/control")
  ).json();
  const expected = before.calls[0];
  await mode(request, "normal");
  await page.getByRole("button", { name: "같은 범위로 다시 시도" }).click();
  await expect(
    page.getByRole("button", { name: /was-2.*상세 보기/ }),
  ).toBeVisible();
  const after = await (
    await request.get("http://127.0.0.1:3102/control")
  ).json();
  expect(after.calls).toEqual([expected]);
});
test("재조회 실패는 기존 목록과 상세를 유지하며 상세 404·잘못된 원문을 구분한다", async ({
  request,
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: /was-2.*상세 보기/ }).click();
  await expect(page.getByLabel("Stack trace 원문")).toBeVisible();
  await mode(request, "unavailable");
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(
    page.getByText("이전 조회 결과를 표시하고 있습니다."),
  ).toBeVisible();
  await expect(page.getByLabel("Stack trace 원문")).toBeVisible();
  await mode(request, "missing");
  await page.getByRole("button", { name: /was-1.*상세 보기/ }).click();
  await expect(page.getByText(/선택한 로그를 찾을 수 없습니다/)).toBeVisible();
  await mode(request, "bad-detail");
  await page.getByRole("button", { name: "상세 다시 시도" }).click();
  await expect(
    page.getByText("로그를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."),
  ).toBeVisible();
});
test("닫거나 새로 선택한 뒤 늦은 상세 응답이 화면을 덮어쓰지 않는다", async ({
  request,
  page,
}) => {
  await page.goto("/");
  await mode(request, "detail-delay");
  await page.getByRole("button", { name: /was-2.*상세 보기/ }).click();
  await expect(page.getByText("상세 로그를 불러오고 있습니다.")).toBeVisible();
  await page.getByRole("button", { name: /was-1.*상세 보기/ }).click();
  await expect(
    page.getByText("이 로그에는 stack trace가 없습니다."),
  ).toBeVisible();
  await page.waitForTimeout(800);
  await expect(page.getByText("늦게 도착한 원문")).toHaveCount(0);
});
test("중계는 고정 GET·시각 조건만 받고 오류·과대·잘못된 응답을 안전하게 처리한다", async ({
  request,
}) => {
  const query =
    "/api/logs?from=2026-10-27T03:00:00.000Z&to=2026-10-27T03:15:00.000Z";
  for (const path of [
    "/api/logs",
    query + "&keyword=test",
    query + "&from=x",
    `/api/logs/${id}?x=1`,
    "/api/logs/not-a-uuid",
  ])
    expect((await request.get(path)).status()).toBe(400);
  expect((await request.post("/api/logs")).status()).toBe(405);
  for (const [value, code] of [
    ["html", "UPSTREAM_INVALID_RESPONSE"],
    ["redirect", "UPSTREAM_INVALID_RESPONSE"],
    ["malformed", "UPSTREAM_INVALID_RESPONSE"],
    ["oversize", "UPSTREAM_RESPONSE_TOO_LARGE"],
    ["timeout", "UPSTREAM_TIMEOUT"],
  ]) {
    await mode(request, value);
    const response = await request.get(query);
    expect(response.status()).toBe(value === "timeout" ? 504 : 502);
    const body = await response.text();
    expect(JSON.parse(body).code).toBe(code);
    expect(body).not.toContain("sensitive upstream");
    expect(response.headers()["cache-control"]).toBe("no-store");
  }
  await mode(request, "wrong-id");
  expect((await request.get(`/api/logs/${id}`)).status()).toBe(502);
});
test("최소 백엔드의 50건 초과를 빈 목록으로 숨기지 않는다", async ({
  request,
  page,
}) => {
  await mode(request, "pagination");
  await page.goto("/");
  await expect(page.getByText(/로그가 50건을 초과했습니다/)).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "조회 범위에 로그가 없습니다" }),
  ).toHaveCount(0);
});
test("생존 확인과 정적 자산을 제공한다", async ({ request }) => {
  const r = await request.get("/health/live");
  expect(await r.json()).toEqual({ status: "UP" });
  expect((await request.get("/mark.svg")).status()).toBe(200);
});
