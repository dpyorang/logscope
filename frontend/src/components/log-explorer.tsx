"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import {
  detail,
  displayTime,
  failure,
  guidance,
  listing,
  recentRange,
  record,
  type Detail,
  type Listing,
  type Range,
  type Result,
} from "@/lib/logs";

type Selection = { id: string; result: Result<Detail> | null };
async function read<T>(
  url: string,
  valid: (value: unknown) => value is T,
  signal: AbortSignal,
): Promise<Result<T>> {
  try {
    const response = await fetch(url, { cache: "no-store", signal });
    const value: unknown = await response.json();
    if (response.ok)
      return valid(value)
        ? { ok: true, data: value }
        : failure(502, "UPSTREAM_INVALID_RESPONSE");
    return record(value) && typeof value.code === "string"
      ? {
          ok: false,
          problem: {
            status: response.status,
            code: value.code,
            errors: [],
            ...(response.headers.get("retry-after")
              ? { retryAfter: response.headers.get("retry-after")! }
              : {}),
          },
        }
      : failure(502, "UPSTREAM_INVALID_RESPONSE");
  } catch {
    return signal.aborted
      ? failure(499, "REQUEST_CANCELLED")
      : failure(502, "UPSTREAM_UNAVAILABLE");
  }
}
export function LogExplorer({
  initialRange,
  initialResult,
  initialSuccessAt,
}: {
  initialRange: Range;
  initialResult: Result<Listing>;
  initialSuccessAt: string | null;
}) {
  const [result, setResult] = useState(initialResult);
  const [range, setRange] = useState(initialRange);
  const [successAt, setSuccessAt] = useState(initialSuccessAt);
  const [listError, setListError] = useState<{
    range: Range;
    code: string;
    retryAfter?: string;
  } | null>(
    initialResult.ok ? null : { range: initialRange, ...initialResult.problem },
  );
  const [busy, setBusy] = useState(false);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [wrap, setWrap] = useState(true);
  const [copyState, setCopyState] = useState("");
  const listController = useRef<AbortController | null>(null);
  const detailController = useRef<AbortController | null>(null);
  const detailVersion = useRef(0);
  const lastTrigger = useRef<HTMLButtonElement | null>(null);
  const detailHeading = useRef<HTMLHeadingElement | null>(null);
  const listHeading = useRef<HTMLHeadingElement | null>(null);
  const panel = useRef<HTMLElement | null>(null);
  const selectedId = selection?.id;
  useEffect(() => {
    if (selectedId) detailHeading.current?.focus();
  }, [selectedId]);
  useEffect(
    () => () => {
      listController.current?.abort();
      detailController.current?.abort();
      detailVersion.current += 1;
      listController.current = null;
    },
    [],
  );

  async function queryList(nextRange: Range) {
    listController.current?.abort();
    const controller = new AbortController();
    listController.current = controller;
    detailController.current?.abort();
    detailVersion.current += 1;
    setSelection((previous) =>
      previous && !previous.result
        ? {
            id: previous.id,
            result: failure(499, "DETAIL_REQUEST_INTERRUPTED"),
          }
        : previous,
    );
    setBusy(true);
    const timer = setTimeout(() => controller.abort(), 8000);
    const next = await read(
      `/api/logs?${new URLSearchParams(nextRange)}`,
      listing,
      controller.signal,
    );
    clearTimeout(timer);
    if (listController.current !== controller) return;
    setBusy(false);
    if (next.ok) {
      const restore = panel.current?.contains(document.activeElement);
      setResult(next);
      setRange(nextRange);
      setSuccessAt(new Date().toISOString());
      setListError(null);
      setSelection(null);
      setCopyState("");
      if (restore) requestAnimationFrame(() => listHeading.current?.focus());
    } else
      setListError({
        range: nextRange,
        ...next.problem,
        code:
          next.problem.code === "REQUEST_CANCELLED"
            ? "UPSTREAM_TIMEOUT"
            : next.problem.code,
      });
  }
  async function queryDetail(id: string, trigger?: HTMLButtonElement) {
    if (trigger) lastTrigger.current = trigger;
    detailController.current?.abort();
    const controller = new AbortController();
    detailController.current = controller;
    const version = ++detailVersion.current;
    setSelection({ id, result: null });
    setCopyState("");
    setWrap(true);
    const timer = setTimeout(() => controller.abort(), 8000);
    const next = await read(
      `/api/logs/${id}`,
      (value): value is Detail => detail(value) && value.eventId === id,
      controller.signal,
    );
    clearTimeout(timer);
    if (detailVersion.current !== version) return;
    setSelection({
      id,
      result:
        !next.ok && next.problem.code === "REQUEST_CANCELLED"
          ? failure(504, "UPSTREAM_TIMEOUT")
          : next,
    });
  }
  function closeDetail() {
    detailController.current?.abort();
    detailVersion.current += 1;
    setSelection(null);
    setCopyState("");
    if (lastTrigger.current?.isConnected) lastTrigger.current.focus();
    else listHeading.current?.focus();
  }
  async function copyTrace(value: string) {
    const version = detailVersion.current;
    try {
      await navigator.clipboard.writeText(value);
      if (version === detailVersion.current)
        setCopyState("Stack trace를 복사했습니다.");
    } catch {
      if (version === detailVersion.current)
        setCopyState("복사하지 못했습니다. 원문을 선택해 복사해 주세요.");
    }
  }
  const current = selection?.result?.ok ? selection.result.data : null;
  return (
    <div className="shell explorer-shell">
      <header className="site-header">
        <div className="header-context">
          <Link className="brand" href="/" aria-label="logscope 홈">
            <span className="brand-mark" aria-hidden="true">
              L
            </span>
            logscope
          </Link>
          <span className="header-page">로그 탐색</span>
        </div>
        <span className="environment-label">개발 환경</span>
      </header>
      <main id="main-content" className="explorer-main" tabIndex={-1}>
        <div className="page-title">
          <div>
            <p className="eyebrow">서버 로그 관측</p>
            <h1>로그 탐색</h1>
            <p className="intro">
              발생한 서버를 확인하고 로그의 상세 내용을 살펴보세요.
            </p>
          </div>
          <button
            className="primary-button"
            disabled={busy}
            onClick={() => queryList(recentRange())}
          >
            {busy ? "조회 중…" : "새로고침"}
          </button>
        </div>
        <div className="query-info">
          <div>
            <strong>최근 24시간 · 최신순 · 최대 50건</strong>
            <p>
              조회 범위: <time>{displayTime(range.from)}</time> ~{" "}
              <time>{displayTime(range.to)}</time>
            </p>
            <p>시작 시각 포함 · 종료 시각 제외</p>
          </div>
          <p>
            {successAt ? (
              <>
                마지막 성공 조회
                <br />
                <time>{displayTime(successAt)}</time>
              </>
            ) : (
              "아직 성공한 조회가 없습니다"
            )}
          </p>
        </div>
        <p className="query-status" role="status" aria-live="polite">
          {busy
            ? "목록을 갱신하고 있습니다. 기존 결과는 이전 조회 결과입니다."
            : result.ok
              ? `현재 목록 ${result.data.items.length}건`
              : "목록 조회 실패"}
        </p>
        {listError && (
          <div className="error-notice" role="alert">
            <strong>{guidance(listError.code)}</strong>
            {result.ok && <p>이전 조회 결과를 표시하고 있습니다.</p>}
            <p>
              실패한 조회 범위: {displayTime(listError.range.from)} ~{" "}
              {displayTime(listError.range.to)}
            </p>
            {listError.retryAfter && (
              <p>
                서버가 재시도 대기를 요청했습니다. 잠시 후 다시 시도해 주세요.
              </p>
            )}
            <button disabled={busy} onClick={() => queryList(listError.range)}>
              같은 범위로 다시 시도
            </button>
          </div>
        )}
        <div className="explorer-grid">
          <section
            className="list-panel"
            aria-labelledby="list-heading"
            aria-busy={busy}
          >
            <h2 id="list-heading" ref={listHeading} tabIndex={-1}>
              로그 목록
            </h2>
            {result.ok && result.data.items.length === 0 && (
              <div className="empty-state">
                <h3>조회 범위에 로그가 없습니다</h3>
                <p>
                  최근 24시간에 발생한 로그만 표시합니다. 로그를 새로 전송한 뒤
                  새로고침해 주세요.
                </p>
              </div>
            )}
            {result.ok && result.data.items.length > 0 && (
              <div
                className="table-scroll"
                tabIndex={0}
                role="region"
                aria-label="로그 목록 표 가로 스크롤"
              >
                <table>
                  <caption className="sr-only">
                    발생 시각 최신순의 로그 목록
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">발생 시각 (UTC)</th>
                      <th scope="col">레벨</th>
                      <th scope="col">서버</th>
                      <th scope="col">메시지</th>
                      <th scope="col">상세</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.data.items.map((item) => (
                      <tr
                        key={item.eventId}
                        className={
                          selection?.id === item.eventId ? "selected" : ""
                        }
                      >
                        <td className="timestamp">
                          {displayTime(item.occurredAt)}
                        </td>
                        <td>
                          <span
                            className={
                              item.level === "ERROR"
                                ? "level error-level"
                                : "level"
                            }
                          >
                            {item.level}
                          </span>
                        </td>
                        <td>{item.instanceId}</td>
                        <td>
                          <span className="message-summary">
                            {item.message}
                          </span>
                          {item.hasException && (
                            <span className="exception-label">예외 있음</span>
                          )}
                        </td>
                        <td>
                          <button
                            aria-label={`${item.instanceId} ${item.occurredAt} 로그 상세 보기`}
                            aria-pressed={selection?.id === item.eventId}
                            aria-disabled={busy}
                            onClick={(event) => {
                              if (!busy)
                                void queryDetail(
                                  item.eventId,
                                  event.currentTarget,
                                );
                            }}
                          >
                            상세 보기
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {!result.ok && (
              <p className="empty-state">
                조회에 성공하면 로그가 여기에 표시됩니다.
              </p>
            )}
          </section>
          <section
            className="detail-panel"
            ref={panel}
            aria-labelledby="detail-heading"
          >
            <div className="panel-title">
              <h2 id="detail-heading" ref={detailHeading} tabIndex={-1}>
                로그 상세
              </h2>
              {selection && <button onClick={closeDetail}>닫기</button>}
            </div>
            {!selection && (
              <p className="empty-state">
                로그를 선택하면 상세 내용을 확인할 수 있습니다.
              </p>
            )}
            {selection && !selection.result && (
              <p role="status">상세 로그를 불러오고 있습니다.</p>
            )}
            {selection?.result && !selection.result.ok && (
              <div role="alert">
                <p>{guidance(selection.result.problem.code)}</p>
                <button
                  disabled={busy}
                  onClick={() => queryDetail(selection.id)}
                >
                  상세 다시 시도
                </button>
              </div>
            )}
            {current && (
              <>
                <dl className="metadata">
                  <dt>발생 시각</dt>
                  <dd>{displayTime(current.occurredAt)}</dd>
                  <dt>서비스</dt>
                  <dd>{current.serviceName}</dd>
                  <dt>서버</dt>
                  <dd>{current.instanceId}</dd>
                  <dt>레벨</dt>
                  <dd>{current.level}</dd>
                  <dt>이벤트 ID</dt>
                  <dd>{current.eventId}</dd>
                </dl>
                <h3>메시지 원문</h3>
                <pre className="message-full">{current.message}</pre>
                <div className="trace-title">
                  <h3>Stack trace</h3>
                  {current.exception !== null && (
                    <div>
                      <button
                        aria-pressed={wrap}
                        onClick={() => setWrap(!wrap)}
                      >
                        줄바꿈 {wrap ? "켜짐" : "꺼짐"}
                      </button>
                      <button onClick={() => copyTrace(current.exception!)}>
                        원문 복사
                      </button>
                    </div>
                  )}
                </div>
                {current.exception === null ? (
                  <p>이 로그에는 stack trace가 없습니다.</p>
                ) : (
                  <pre
                    className={wrap ? "trace wrapped" : "trace"}
                    tabIndex={0}
                    aria-label="Stack trace 원문"
                  >
                    {current.exception}
                  </pre>
                )}
                <p role="status" className="copy-status">
                  {copyState}
                </p>
              </>
            )}
          </section>
        </div>
        <p className="footnote">
          검색·필터·페이지 탐색은 다음 단계에서 제공됩니다.
        </p>
      </main>
      <footer>
        logscope<span>서버와 원인을 한곳에서</span>
      </footer>
    </div>
  );
}
