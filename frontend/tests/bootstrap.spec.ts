import { expect, test } from "@playwright/test";

test("JavaScript 없이도 한글 초기 화면을 서버에서 렌더링한다", async ({
  browser,
}) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  const response = await page.goto("http://127.0.0.1:3100/");
  expect(response?.status()).toBe(200);
  expect(response?.headers()["cache-control"]).toContain("no-store");
  await expect(page).toHaveTitle("logscope | 로그 관측");
  await expect(page.locator("html")).toHaveAttribute("lang", "ko");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "흩어진 로그",
  );
  await expect(page.getByRole("heading", { level: 2 })).toHaveText(
    "로그 조회 연결을 준비하고 있습니다",
  );
  await context.close();
});

test("키보드와 좁은 화면에서 기본 화면에 접근하고 API를 호출하지 않는다", async ({
  page,
}) => {
  const errors: string[] = [];
  const apiRequests: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/"))
      apiRequests.push(request.url());
  });
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "본문으로 바로가기" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  await page.setViewportSize({ width: 360, height: 800 });
  await expect(page.getByRole("heading", { level: 2 })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
  expect(apiRequests).toEqual([]);
});

test("생존 확인과 정적 자산을 standalone 서버에서 제공한다", async ({
  request,
}) => {
  const response = await request.get("/health/live");
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ status: "UP" });
  expect(response.headers()["cache-control"]).toBe("no-store");
  expect((await request.get("/mark.svg")).status()).toBe(200);
});
