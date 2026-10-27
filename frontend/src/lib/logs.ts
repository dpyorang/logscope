export type Range = { from: string; to: string };
export type Summary = {
  eventId: string;
  occurredAt: string;
  serviceName: string;
  instanceId: string;
  level: string;
  message: string;
  hasException: boolean;
};
export type Detail = Omit<Summary, "hasException"> & {
  exception: string | null;
};
export type Listing = {
  items: Summary[];
  size: number;
  nextCursor: null;
  previousCursor: null;
};
export type Problem = {
  status: number;
  code: string;
  errors: { field: string; code: string }[];
  retryAfter?: string;
};
export type Result<T> = { ok: true; data: T } | { ok: false; problem: Problem };
export const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
export function utc(value: unknown): value is string {
  if (
    typeof value !== "string" ||
    !/^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/.test(
      value,
    )
  )
    return false;
  const time = Date.parse(value);
  return Number.isFinite(time) && new Date(time).toISOString() === value;
}
export function validRange(range: Range) {
  return (
    utc(range.from) &&
    utc(range.to) &&
    Date.parse(range.from) < Date.parse(range.to) &&
    Date.parse(range.to) - Date.parse(range.from) <= 86_400_000
  );
}
export function recentRange(): Range {
  const to = new Date();
  return {
    from: new Date(to.getTime() - 86_400_000).toISOString(),
    to: to.toISOString(),
  };
}
export function displayTime(value: string) {
  return value.replace("T", " ").replace("Z", " UTC");
}
function text(value: unknown, max: number): value is string {
  return (
    typeof value === "string" &&
    value.isWellFormed() &&
    value.trim().length > 0 &&
    Array.from(value).length <= max
  );
}
export function summary(value: unknown): value is Summary {
  return (
    record(value) &&
    typeof value.eventId === "string" &&
    UUID.test(value.eventId) &&
    utc(value.occurredAt) &&
    text(value.serviceName, 64) &&
    text(value.instanceId, 64) &&
    text(value.level, 32) &&
    text(value.message, 4096) &&
    typeof value.hasException === "boolean"
  );
}
export function listing(value: unknown): value is Listing {
  return (
    record(value) &&
    value.size === 50 &&
    Array.isArray(value.items) &&
    value.items.length <= 50 &&
    value.items.every(summary) &&
    value.nextCursor === null &&
    value.previousCursor === null &&
    new Set(value.items.map((item) => item.eventId)).size === value.items.length
  );
}
export function detail(value: unknown): value is Detail {
  return (
    record(value) &&
    summary({ ...value, hasException: value.exception !== null }) &&
    (value.exception === null || text(value.exception, 32768))
  );
}
export function failure(status: number, code: string): Result<never> {
  return { ok: false, problem: { status, code, errors: [] } };
}
export function guidance(code: string) {
  switch (code) {
    case "LOG_NOT_FOUND":
      return "선택한 로그를 찾을 수 없습니다. 목록에서 다른 로그를 선택해 주세요.";
    case "PAGINATION_NOT_IMPLEMENTED":
      return "현재 조회 범위의 로그가 50건을 초과했습니다. 페이지 탐색은 다음 단계에서 제공됩니다.";
    case "UPSTREAM_TIMEOUT":
      return "조회 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.";
    case "UPSTREAM_RESPONSE_TOO_LARGE":
      return "로그 응답이 허용 크기를 초과했습니다. 조회를 완료하지 못했습니다.";
    case "API_CONFIGURATION_ERROR":
      return "로그 조회 연결 설정을 확인해 주세요.";
    default:
      return "로그를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
