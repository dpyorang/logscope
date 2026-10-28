import { expect, test } from "@playwright/test";
import {
  canonicalUtc,
  fromDraft,
  parseSearch,
  searchResult,
} from "../src/lib/search";
const base = {
  from: "2026-10-27T00:00:00.000Z",
  to: "2026-10-28T00:00:00.000Z",
};
test("검색 기간·Unicode 경계와 모호한 중복 조건을 거부한다", () => {
  expect(canonicalUtc("2026-02-30T00:00:00Z")).toBeNull();
  expect(canonicalUtc("2026-10-27T00:00:00.1Z")).toBe(
    "2026-10-27T00:00:00.100Z",
  );
  expect(parseSearch(new URLSearchParams(base)).ok).toBe(true);
  expect(
    parseSearch(
      new URLSearchParams({ ...base, to: "2026-10-28T00:00:00.001Z" }),
    ).ok,
  ).toBe(false);
  const form = {
    from: base.from.slice(0, -1),
    to: base.to.slice(0, -1),
    level: "",
    instanceId: "",
    keyword: "😀".repeat(200),
  };
  expect(fromDraft(form).ok).toBe(true);
  expect(fromDraft({ ...form, keyword: "😀".repeat(201) }).ok).toBe(false);
  expect(fromDraft({ ...form, keyword: "\ud800" }).ok).toBe(false);
  const duplicate = new URLSearchParams(base);
  duplicate.append("from", base.from);
  expect(parseSearch(duplicate).ok).toBe(false);
  expect(parseSearch(new URLSearchParams({ ...base, keyword: " " })).ok).toBe(
    false,
  );
  expect(
    parseSearch(new URLSearchParams({ ...base, cursor: "unsupported" })).ok,
  ).toBe(false);
});
test("빈 검색의 색인 대기 정합성과 잘못된 제한을 검사한다", () => {
  const query = { ...base, size: 50 };
  const result = {
    items: [],
    size: 50,
    truncated: false,
    synchronization: {
      pendingCount: 3,
      retryingCount: 1,
      indexingPending: true,
    },
  };
  expect(searchResult(result, query)).toBe(true);
  expect(searchResult({ ...result, truncated: true }, query)).toBe(false);
  expect(
    searchResult(
      {
        ...result,
        synchronization: {
          pendingCount: 0,
          retryingCount: 1,
          indexingPending: false,
        },
      },
      query,
    ),
  ).toBe(false);
  expect(
    searchResult(
      {
        ...result,
        synchronization: {
          pendingCount: 3,
          retryingCount: 1,
          indexingPending: false,
        },
      },
      query,
    ),
  ).toBe(false);
});
