import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("실제 MySQL에 저장한 합성 로그를 SSR 목록과 상세에서 읽는다", async ({
  request,
  page,
  browser,
}) => {
  const eventId = randomUUID();
  const message = "격리된 실제 저장·조회 확인 " + eventId;
  const exception =
    "java.lang.IllegalStateException: synthetic failure\n\tat com.logscope.sample.DemoService.run(DemoService.java:24)";
  const receipt = await request.post(
    `${process.env.LOGSCOPE_TEST_API_URL}/api/v1/logs`,
    {
      data: {
        eventId,
        occurredAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
        serviceName: "sample-was",
        instanceId: "was-2",
        level: "ERROR",
        message,
        exception,
      },
    },
  );
  expect(receipt.status()).toBe(201);
  expect(await receipt.json()).toEqual({ eventId, status: "STORED" });
  const context = await browser.newContext({ javaScriptEnabled: false });
  const raw = await context.newPage();
  await raw.goto(process.env.LOGSCOPE_TEST_UI_URL!);
  await expect(raw.getByText(message, { exact: true })).toBeVisible();
  await context.close();
  await page.goto("/");
  await page
    .getByRole("row")
    .filter({ hasText: message })
    .getByRole("button")
    .click();
  await expect(
    page.getByRole("heading", { name: "로그 상세", exact: true }),
  ).toBeFocused();
  await expect(page.locator(".metadata")).toContainText("was-2");
  await expect(page.locator(".metadata")).toContainText(eventId);
  await expect(page.locator(".message-full")).toHaveText(message);
  await expect(page.getByLabel("Stack trace 원문")).toHaveText(exception);
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: message }).getByRole("button"),
  ).toBeFocused();
});

test("실제 저장 로그를 SSE로 이어받고 정지 중 저장된 로그를 재개로 복구한다", async ({
  request,
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/logs/stream?**", async (route) => {
    await gate;
    await route.continue();
  });
  await page.goto("/");
  const first = randomUUID();
  const second = randomUUID();
  async function save(eventId: string) {
    const receipt = await request.post(
      `${process.env.LOGSCOPE_TEST_API_URL}/api/v1/logs`,
      {
        data: {
          eventId,
          occurredAt: new Date(Date.now() - 1000).toISOString(),
          serviceName: "sample-was",
          instanceId: "was-live",
          level: "INFO",
          message: `실제 SSE 복구 ${eventId}`,
          exception: null,
        },
      },
    );
    expect(receipt.status()).toBe(201);
  }
  await save(first);
  release();
  await expect(
    page.getByText(`실제 SSE 복구 ${first}`, { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("row")
    .filter({ hasText: first })
    .getByRole("button")
    .click();
  await expect(page.locator(".message-full")).toHaveText(
    `실제 SSE 복구 ${first}`,
  );
  await page.getByRole("button", { name: "일시정지", exact: true }).click();
  await save(second);
  await expect(
    page.locator("tbody").getByText(`실제 SSE 복구 ${second}`, { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "실시간 재개", exact: true }).click();
  await expect(
    page.locator("tbody").getByText(`실제 SSE 복구 ${second}`, { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".message-full")).toHaveText(
    `실제 SSE 복구 ${first}`,
  );
  await page.getByRole("button", { name: "새로고침", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "닫기", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.locator("tbody").getByText(`실제 SSE 복구 ${second}`, { exact: true }),
  ).toBeVisible();
});
