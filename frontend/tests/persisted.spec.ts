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
