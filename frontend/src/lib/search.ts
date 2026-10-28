import {
  record,
  summary,
  utc,
  validRange,
  type Problem,
  type Range,
  type Result,
} from "./logs";
import { compare, inRange } from "./live";

export const SEARCH_SERVER_TIMEOUT = 15_000;
export const SEARCH_BROWSER_TIMEOUT = 18_000;
export const LEVELS = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"] as const;
// 서버 선택지는 현재 페이지의 로그에서 추출하지 않는 설정값이다.
export const SERVER_OPTIONS = ["was-1", "was-2", "was-3"] as const;
export type SearchQuery = Range & {
  level?: string;
  instanceId?: string;
  keyword?: string;
  size: number;
};
export type SearchResult = {
  items: import("./logs").Summary[];
  size: number;
  truncated: boolean;
  synchronization: {
    pendingCount: number;
    retryingCount: number;
    indexingPending: boolean;
  };
};
export type SearchDraft = {
  from: string;
  to: string;
  level: string;
  instanceId: string;
  keyword: string;
};
export function draft(range: Range): SearchDraft {
  return {
    from: range.from.slice(0, -1),
    to: range.to.slice(0, -1),
    level: "",
    instanceId: "",
    keyword: "",
  };
}
export function canonicalUtc(value: string): string | null {
  if (
    !/^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,3})?Z$/.test(
      value,
    )
  )
    return null;
  const normalized = value.includes(".")
    ? value.slice(0, -1).padEnd(23, "0") + "Z"
    : value.slice(0, -1) + ".000Z";
  return utc(normalized) ? normalized : null;
}
export function parameters(query: SearchQuery) {
  const { size, ...rest } = query;
  return new URLSearchParams({ ...rest, size: String(size) });
}
function invalid(field: string, code: string): Result<never> {
  return {
    ok: false,
    problem: {
      status: 400,
      code: "VALIDATION_ERROR",
      errors: [{ field, code }],
    },
  };
}
export function parseSearch(params: URLSearchParams): Result<SearchQuery> {
  const fields = new Set([
    "from",
    "to",
    "level",
    "instanceId",
    "keyword",
    "size",
  ]);
  for (const key of params.keys()) {
    if (!fields.has(key)) return invalid("query", "UNKNOWN_FIELD");
    if (params.getAll(key).length !== 1)
      return invalid(key, "DUPLICATE_PARAMETER");
    if (!params.get(key)?.trim()) return invalid(key, "REQUIRED");
  }
  const from = canonicalUtc(params.get("from") ?? ""),
    to = canonicalUtc(params.get("to") ?? "");
  if (!from)
    return invalid(
      "from",
      params.has("from") ? "INVALID_UTC_TIMESTAMP" : "REQUIRED",
    );
  if (!to)
    return invalid(
      "to",
      params.has("to") ? "INVALID_UTC_TIMESTAMP" : "REQUIRED",
    );
  if (!validRange({ from, to })) return invalid("to", "OUT_OF_RANGE");
  const level = params.get("level"),
    instanceId = params.get("instanceId"),
    keyword = params.get("keyword")?.trim();
  if (level !== null && !LEVELS.some((value) => value === level))
    return invalid("level", "INVALID_ENUM");
  if (instanceId !== null && !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(instanceId))
    return invalid("instanceId", "INVALID_FORMAT");
  if (
    keyword !== undefined &&
    (!keyword.isWellFormed() || Array.from(keyword).length > 200)
  )
    return invalid("keyword", "TOO_LONG");
  const size = params.get("size") ?? "50";
  if (!/^[0-9]{1,3}$/.test(size) || Number(size) < 1 || Number(size) > 100)
    return invalid("size", "OUT_OF_RANGE");
  return {
    ok: true,
    data: {
      from,
      to,
      size: Number(size),
      ...(level ? { level } : {}),
      ...(instanceId ? { instanceId } : {}),
      ...(keyword ? { keyword } : {}),
    },
  };
}
export function fromDraft(value: SearchDraft): Result<SearchQuery> {
  const local = (text: string) =>
    text.length === 16 ? text + ":00Z" : text + "Z";
  if (!value.keyword.isWellFormed())
    return invalid("keyword", "INVALID_FORMAT");
  return parseSearch(
    new URLSearchParams({
      from: local(value.from),
      to: local(value.to),
      size: "50",
      ...(value.level ? { level: value.level } : {}),
      ...(value.instanceId ? { instanceId: value.instanceId } : {}),
      ...(value.keyword.trim() ? { keyword: value.keyword.trim() } : {}),
    }),
  );
}
export function searchResult(
  value: unknown,
  query: SearchQuery,
): value is SearchResult {
  if (
    !record(value) ||
    value.size !== query.size ||
    typeof value.truncated !== "boolean" ||
    !Array.isArray(value.items) ||
    value.items.length > query.size ||
    !value.items.every(summary) ||
    (value.truncated && value.items.length !== query.size)
  )
    return false;
  const sync = value.synchronization;
  if (
    !record(sync) ||
    !Number.isSafeInteger(sync.pendingCount) ||
    !Number.isSafeInteger(sync.retryingCount) ||
    typeof sync.pendingCount !== "number" ||
    typeof sync.retryingCount !== "number" ||
    sync.pendingCount < 0 ||
    sync.retryingCount < 0 ||
    sync.retryingCount > sync.pendingCount ||
    sync.indexingPending !== sync.pendingCount > 0
  )
    return false;
  return (
    new Set(value.items.map((item) => item.eventId)).size ===
      value.items.length &&
    value.items.every(
      (item, i, items) =>
        inRange(item, query) &&
        (!query.level || item.level === query.level) &&
        (!query.instanceId || item.instanceId === query.instanceId) &&
        (i === 0 || compare(items[i - 1], item) <= 0),
    )
  );
}
export function searchGuidance(code: string) {
  switch (code) {
    case "VALIDATION_ERROR":
      return "검색 조건을 확인해 주세요. 기간은 최대 24시간이며 메시지는 200자까지 입력할 수 있습니다.";
    case "SEARCH_UNAVAILABLE":
      return "검색 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    case "SEARCH_INDEX_REBUILDING":
      return "검색 색인을 준비하고 있습니다. 잠시 후 같은 조건으로 다시 조회해 주세요.";
    case "SEARCH_CAPACITY_EXCEEDED":
      return "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
    case "STORAGE_UNAVAILABLE":
      return "로그 저장소에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    case "UPSTREAM_TIMEOUT":
      return "검색 시간이 초과됐습니다. 같은 조건으로 다시 시도해 주세요.";
    default:
      return "검색을 완료하지 못했습니다. 잠시 후 같은 조건으로 다시 시도해 주세요.";
  }
}
export function fieldErrors(problem: Problem): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const item of problem.errors) {
    if (!["from", "to", "level", "instanceId", "keyword"].includes(item.field))
      continue;
    errors[item.field] =
      item.field === "keyword"
        ? "메시지는 올바른 문자로 200자까지 입력해 주세요."
        : item.field === "from" || item.field === "to"
          ? "UTC 시각을 입력하고 시작보다 늦은 종료 시각과 최대 24시간 범위를 확인해 주세요."
          : "목록에서 올바른 값을 선택해 주세요.";
  }
  return errors;
}
