import { record, summary, validRange, type Range, type Summary } from "./logs";

export type Snapshot = Range & {
  items: Summary[];
  cursor: string;
  truncated: boolean;
};
// 불투명 토큰이다. 내부 순서를 JavaScript 숫자로 변환하지 않는다.
export function cursor(value: unknown): value is string {
  return typeof value === "string" && /^[\x21-\x7e]{1,56}$/.test(value);
}
export function compare(a: Summary, b: Summary) {
  return (
    b.occurredAt.localeCompare(a.occurredAt) ||
    b.eventId.localeCompare(a.eventId)
  );
}
export function inRange(item: Summary, range: Range) {
  return item.occurredAt >= range.from && item.occurredAt < range.to;
}
export function snapshot(value: unknown): value is Snapshot {
  if (
    !record(value) ||
    !cursor(value.cursor) ||
    typeof value.from !== "string" ||
    typeof value.to !== "string" ||
    !validRange({ from: value.from, to: value.to }) ||
    Date.parse(value.to) - Date.parse(value.from) !== 86_400_000 ||
    typeof value.truncated !== "boolean" ||
    !Array.isArray(value.items) ||
    value.items.length > 50 ||
    !value.items.every(summary)
  )
    return false;
  const range = { from: value.from, to: value.to };
  return (
    new Set(value.items.map((item) => item.eventId)).size ===
      value.items.length &&
    value.items.every(
      (item, i, items) =>
        inRange(item, range) && (i === 0 || compare(items[i - 1], item) <= 0),
    )
  );
}
export function windowAt(to: number): Range {
  return {
    from: new Date(to - 86_400_000).toISOString(),
    to: new Date(to).toISOString(),
  };
}
export function merge(
  snapshot: Snapshot,
  range: Range,
  item?: Summary,
): Snapshot {
  const retained = snapshot.items.filter((value) => inRange(value, range));
  if (
    item &&
    inRange(item, range) &&
    !retained.some((value) => value.eventId === item.eventId)
  )
    retained.push(item);
  retained.sort(compare);
  return {
    ...snapshot,
    ...range,
    items: retained.slice(0, 50),
    truncated: snapshot.truncated || retained.length > 50,
  };
}
