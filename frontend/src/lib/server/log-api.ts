import "server-only";
import { snapshot, type Snapshot } from "../live";
import { readFile } from "node:fs/promises";
import {
  detail,
  failure,
  listing,
  record,
  type Detail,
  type Listing,
  type Problem,
  type Range,
  type Result,
} from "../logs";

const DEADLINE = 5000;
const CODES: Record<string, number> = {
  VALIDATION_ERROR: 400,
  LIVE_CURSOR_INVALID: 409,
  LIVE_CURSOR_AHEAD: 409,
  LIVE_CAPACITY_EXCEEDED: 503,
  LOG_NOT_FOUND: 404,
  INTERNAL_ERROR: 500,
  STORAGE_UNAVAILABLE: 503,
  QUERY_FEATURE_NOT_IMPLEMENTED: 501,
  PAGINATION_NOT_IMPLEMENTED: 501,
};
const FIELDS = new Set([
  "from",
  "to",
  "size",
  "eventId",
  "query",
  "after",
  "Last-Event-ID",
]);
const FIELD_CODES = new Set([
  "REQUIRED",
  "INVALID_FORMAT",
  "INVALID_UTC_TIMESTAMP",
  "OUT_OF_RANGE",
  "UNKNOWN_FIELD",
  "DUPLICATE_PARAMETER",
]);
export async function baseUrl(): Promise<URL> {
  const direct = process.env.LOGSCOPE_API_BASE_URL;
  const file = process.env.LOGSCOPE_API_BASE_URL_FILE;
  if (!!direct === !!file) throw new Error("configuration");
  const value = file ? (await readFile(file, "utf8")).trim() : direct!;
  const url = new URL(value);
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== "/"
  )
    throw new Error("configuration");
  return url;
}
export async function boundedJson(
  response: Response,
  limit: number,
): Promise<unknown> {
  const length = response.headers.get("content-length");
  if (length && Number(length) > limit) throw new Error("large");
  if (!response.body) throw new Error("invalid");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > limit) throw new Error("large");
      chunks.push(next.value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    throw new Error("invalid");
  }
}
export function problem(value: unknown, status: number): Problem | null {
  if (
    !record(value) ||
    value.status !== status ||
    typeof value.code !== "string" ||
    CODES[value.code] !== status ||
    !Array.isArray(value.errors)
  )
    return null;
  const errors: Problem["errors"] = [];
  for (const item of value.errors) {
    if (
      !record(item) ||
      typeof item.field !== "string" ||
      !FIELDS.has(item.field) ||
      typeof item.code !== "string" ||
      !FIELD_CODES.has(item.code)
    )
      return null;
    errors.push({ field: item.field, code: item.code });
  }
  return { status, code: value.code, errors };
}
async function query<T>(
  path: string,
  valid: (value: unknown) => value is T,
  limit: number,
  signal?: AbortSignal,
): Promise<Result<T>> {
  let base: URL;
  try {
    base = await baseUrl();
  } catch {
    return failure(503, "API_CONFIGURATION_ERROR");
  }
  const deadline = AbortSignal.timeout(DEADLINE);
  try {
    const response = await fetch(new URL(path, base), {
      cache: "no-store",
      redirect: "manual",
      headers: { Accept: "application/json, application/problem+json" },
      signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
    });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      return failure(502, "UPSTREAM_INVALID_RESPONSE");
    }
    const type = response.headers
      .get("content-type")
      ?.split(";")[0]
      .trim()
      .toLowerCase();
    if (
      type !== (response.ok ? "application/json" : "application/problem+json")
    ) {
      await response.body?.cancel();
      return failure(502, "UPSTREAM_INVALID_RESPONSE");
    }
    const value = await boundedJson(response, response.ok ? limit : 65_536);
    if (!response.ok) {
      const error = problem(value, response.status);
      if (!error) return failure(502, "UPSTREAM_INVALID_RESPONSE");
      const after = response.headers.get("retry-after");
      if (
        after &&
        ((/^\d{1,5}$/.test(after) && Number(after) <= 86400) ||
          (after.length <= 64 &&
            Number.isFinite(Date.parse(after)) &&
            !/[\r\n]/.test(after)))
      )
        error.retryAfter = after;
      return { ok: false, problem: error };
    }
    if (response.status !== 200 || !valid(value))
      return failure(502, "UPSTREAM_INVALID_RESPONSE");
    return { ok: true, data: value };
  } catch (error) {
    if (signal?.aborted) return failure(499, "REQUEST_CANCELLED");
    if (deadline.aborted) return failure(504, "UPSTREAM_TIMEOUT");
    if (error instanceof Error && error.message === "large")
      return failure(502, "UPSTREAM_RESPONSE_TOO_LARGE");
    if (error instanceof TypeError) return failure(502, "UPSTREAM_UNAVAILABLE");
    return failure(502, "UPSTREAM_INVALID_RESPONSE");
  }
}
export function loadList(range: Range, signal?: AbortSignal) {
  return query<Listing>(
    `/api/v1/logs?${new URLSearchParams({ ...range, size: "50" })}`,
    listing,
    8 * 1024 * 1024,
    signal,
  );
}
export function loadDetail(id: string, signal?: AbortSignal) {
  return query<Detail>(
    `/api/v1/logs/${id}`,
    (value): value is Detail => detail(value) && value.eventId === id,
    1024 * 1024,
    signal,
  );
}

export function loadSnapshot(signal?: AbortSignal) {
  return query<Snapshot>(
    "/api/v1/logs/live",
    snapshot,
    8 * 1024 * 1024,
    signal,
  );
}
