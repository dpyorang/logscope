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

test("실제 ES 검색 조건·상세·50건 제한과 장애 후 복구를 확인한다", async ({
  page,
  request,
}) => {
  test.setTimeout(90_000);
  const token = `synthetic${randomUUID().replaceAll("-", "")}`;
  const save = async (instanceId: string, level: string, message: string) => {
    const eventId = randomUUID();
    const r = await request.post(
      `${process.env.LOGSCOPE_TEST_API_URL}/api/v1/logs`,
      {
        data: {
          eventId,
          occurredAt: new Date(Date.now() - 2000).toISOString(),
          serviceName: "sample-was",
          instanceId,
          level,
          message,
          exception: null,
        },
      },
    );
    expect(r.status()).toBe(201);
    return eventId;
  };
  const target = await save("was-2", "ERROR", `${token} payment failure`);
  await save("was-1", "ERROR", `${token} payment failure`);
  await save("was-2", "INFO", `${token} payment failure`);
  await save("was-2", "ERROR", `${token} different`);
  const queryTime = Date.now();
  const params = new URLSearchParams({
    from: new Date(queryTime - 86400000).toISOString(),
    to: new Date(queryTime).toISOString(),
    instanceId: "was-2",
    level: "ERROR",
    keyword: `${token} PAYMENT`,
    size: "50",
  });
  await expect
    .poll(
      async () => {
        const r = await request.get(
          `${process.env.LOGSCOPE_TEST_API_URL}/api/v1/logs/search?${params}`,
        );
        return r.ok()
          ? (await r.json()).items.map((x: { eventId: string }) => x.eventId)
          : [];
      },
      { timeout: 30_000 },
    )
    .toEqual([target]);
  await page.goto("/");
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page.getByLabel("서버", { exact: true }).selectOption("was-2");
  await page.getByLabel("레벨", { exact: true }).selectOption("ERROR");
  await page
    .getByLabel("메시지 검색어", { exact: true })
    .fill(`${token} PAYMENT`);
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.locator("tbody tr button").click();
  await expect(page.locator(".metadata")).toContainText(target);
  const container = process.env.LOGSCOPE_TEST_SEARCH_CONTAINER;
  if (!container || !/^logscope-ui-[a-f0-9]{8}-engine$/.test(container))
    throw new Error("Isolated search container is required");
  const { execFileSync } = await import("node:child_process");
  execFileSync("docker", ["pause", container]);
  try {
    await page.getByRole("button", { name: "적용 조건으로 다시 조회" }).click();
    await expect(
      page.getByText(
        "검색 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
        { exact: true },
      ),
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.locator(".metadata")).toContainText(target);
    await expect(page.locator("tbody tr")).toHaveCount(1);
    await save("was-2", "ERROR", `${token} payment recovery`);
    await page
      .getByRole("button", { name: "실시간 보기", exact: true })
      .click();
    await expect(page.locator("tbody")).toContainText(
      `${token} payment recovery`,
    );
  } finally {
    execFileSync("docker", ["unpause", container]);
  }
  const many = `batch${randomUUID().replaceAll("-", "")}`;
  for (let i = 0; i < 51; i++) await save("was-3", "WARN", `${many} ${i}`);
  const batchTime = Date.now();
  const batch = new URLSearchParams({
    ...Object.fromEntries(params),
    from: new Date(batchTime - 86400000).toISOString(),
    to: new Date(batchTime).toISOString(),
    instanceId: "was-3",
    level: "WARN",
    keyword: many,
  });
  await expect
    .poll(
      async () => {
        const r = await request.get(
          `${process.env.LOGSCOPE_TEST_API_URL}/api/v1/logs/search?${batch}`,
        );
        return r.ok() && (await r.json()).truncated;
      },
      { timeout: 30_000 },
    )
    .toBe(true);
  await page.getByRole("button", { name: "검색 보기", exact: true }).click();
  await page
    .getByRole("button", { name: "기간을 최근 24시간으로 설정" })
    .click();
  await page.getByLabel("서버", { exact: true }).selectOption("was-3");
  await page.getByLabel("레벨", { exact: true }).selectOption("WARN");
  await page.getByLabel("메시지 검색어", { exact: true }).fill(many);
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(50);
  await expect(page.locator(".limit-notice")).toBeVisible();
});
