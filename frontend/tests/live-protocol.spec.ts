import { expect, test } from "@playwright/test";
import { SseParser, FRAME_LIMIT, type Frame } from "../src/lib/sse";
import { cursor, merge, snapshot, windowAt } from "../src/lib/live";
import type { Summary } from "../src/lib/logs";
const encoder = new TextEncoder();
const item = (n: number, occurredAt = "2026-10-28T01:00:00.000Z"): Summary => ({
  eventId: `7b2a8c30-8d94-4d5f-a6d8-${String(n).padStart(12, "0")}`,
  occurredAt,
  serviceName: "sample-was",
  instanceId: "was-1",
  level: "INFO",
  message: "합성 로그",
  hasException: false,
});
test("모든 바이트 분할에서 UTF-8·BOM·CRLF·여러 data 줄과 heartbeat를 처리한다", () => {
  const input = encoder.encode(
    '\uFEFF: heartbeat\r\n\r\nevent:log\r\nid:c1\r\ndata:{"message":\r\ndata:"한글😀"}\r\n\r\n',
  );
  for (let cut = 0; cut <= input.length; cut++) {
    const frames: Frame[] = [];
    const parser = new SseParser((frame) => frames.push(frame));
    parser.push(input.slice(0, cut));
    parser.push(input.slice(cut));
    parser.finish();
    expect(frames).toEqual([
      { event: "log", id: "c1", data: '{"message":\n"한글😀"}' },
    ]);
  }
  const frames: Frame[] = [];
  const parser = new SseParser((frame) => frames.push(frame));
  for (const byte of input) parser.push(Uint8Array.of(byte));
  parser.finish();
  expect(frames).toHaveLength(1);
});
test("미완성·잘못된 UTF-8·NUL id와 미완성 프레임 누적 한도를 차단한다", () => {
  const frames: Frame[] = [];
  const parser = new SseParser((frame) => frames.push(frame));
  parser.push(encoder.encode("event:log\nid:c1\ndata:{}\n"));
  expect(() => parser.finish()).toThrow("STREAM_INCOMPLETE_FRAME");
  expect(frames).toHaveLength(0);
  expect(() =>
    new SseParser(() => {}).push(Uint8Array.of(100, 97, 116, 97, 58, 255, 10)),
  ).toThrow();
  expect(() =>
    new SseParser(() => {}).push(encoder.encode("id:c\0bad\n")),
  ).toThrow();
  const bounded = new SseParser(() => {});
  bounded.push(encoder.encode("data:" + "x".repeat(FRAME_LIMIT - 5)));
  expect(() => bounded.push(Uint8Array.of(120))).toThrow(
    "STREAM_FRAME_TOO_LARGE",
  );
  const many = new SseParser(() => {});
  expect(() =>
    many.push(encoder.encode(":ok\n\n".repeat(30_000))),
  ).not.toThrow();
});
test("snapshot 경계·정렬·cursor를 검사하고 50건·중복·동일시각·미래/지연 로그를 제한한다", () => {
  const range = windowAt(Date.parse("2026-10-28T02:00:00.000Z"));
  const initial = {
    ...range,
    items: [item(2), item(1)],
    cursor: "opaque:9007199254740993",
    truncated: false,
  };
  expect(snapshot(initial)).toBe(true);
  expect(cursor(initial.cursor)).toBe(true);
  expect(cursor("x".repeat(57))).toBe(false);
  expect(cursor("bad\nvalue")).toBe(false);
  expect(snapshot({ ...initial, items: [item(1), item(2)] })).toBe(false);
  expect(snapshot({ ...initial, items: [item(1, range.to)] })).toBe(false);
  expect(snapshot({ ...initial, items: [item(1), item(1)] })).toBe(false);
  let next = merge(initial, range, item(2));
  expect(next.items).toHaveLength(2);
  next = merge(next, range, item(3, range.from));
  expect(next.items).toHaveLength(3);
  next = merge(next, range, item(4, range.to));
  expect(next.items).toHaveLength(3);
  next = merge(next, range, item(5, "2026-10-26T00:00:00.000Z"));
  expect(next.items).toHaveLength(3);
  for (let n = 10; n < 80; n++) next = merge(next, range, item(n));
  expect(next.items).toHaveLength(50);
  expect(next.items[0].eventId).toBe(item(79).eventId);
  expect(next.truncated).toBe(true);
  expect(
    merge(next, windowAt(Date.parse(range.to) + 86_400_000)).items,
  ).toHaveLength(0);
});

test("CR 단독 줄끝은 다음 바이트나 EOF를 기다리지 않고 프레임을 처리한다", () => {
  const frames: Frame[] = [];
  const parser = new SseParser((frame) => frames.push(frame));
  parser.push(encoder.encode("event:log\rid:c1\rdata:한글\r\r"));
  expect(frames).toEqual([{ event: "log", id: "c1", data: "한글" }]);
  // 앞 프레임의 CR 뒤 LF가 별도 조각으로 도착해도 새 이벤트를 손상하지 않는다.
  parser.push(encoder.encode("\nevent:ready\ndata:{}\n\n"));
  parser.finish();
  expect(frames).toHaveLength(2);
});
