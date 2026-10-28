"use client";

import Link from "next/link";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import {
  detail,
  displayTime,
  failure,
  guidance,
  recentRange,
  type Detail,
  type Range,
  type Result,
} from "@/lib/logs";

import { LiveStore, read } from "@/lib/live-store";
import type { Snapshot } from "@/lib/live";
import { SearchStore } from "@/lib/search-store";
import {
  draft,
  fromDraft,
  fieldErrors,
  searchGuidance,
  type SearchQuery,
} from "@/lib/search";
import { SearchForm } from "./search-form";

type Selection = { id: string; result: Result<Detail> | null };
export function LogExplorer({
  initialRange,
  initialResult,
  initialSuccessAt,
}: {
  initialRange: Range;
  initialResult: Result<Snapshot>;
  initialSuccessAt: string | null;
}) {
  const [store] = useState(
    () => new LiveStore(initialResult, initialRange, initialSuccessAt),
  );
  const [search] = useState(() => new SearchStore());
  const [mode, setMode] = useState<"live" | "search">("live");
  const [input, setInput] = useState(() => draft(initialRange));
  const [validation, setValidation] = useState<Record<string, string>>({});
  const enteredSearch = useRef(false);
  const searched = useSyncExternalStore(
    search.subscribe,
    search.getSnapshot,
    search.getServerSnapshot,
  );
  const isSearch = mode === "search";
  const reading = useRef<{
    element: HTMLElement | null;
    top: number;
    y: number;
    x: number;
    left: number;
    focus: HTMLElement | null;
  } | null>(null);
  const table = useRef<HTMLDivElement | null>(null);
  const subscribe = useCallback(
    (listener: () => void) =>
      store.subscribe(() => {
        if (!reading.current) {
          const detail = document.querySelector<HTMLElement>(
            ".detail-panel .trace, .detail-panel .message-full",
          );
          const bounds = detail?.getBoundingClientRect();
          const anchor =
            detail && bounds && bounds.bottom > 80 && bounds.top < innerHeight
              ? detail
              : (Array.from(
                  document.querySelectorAll<HTMLElement>("tbody tr"),
                ).find((row) => {
                  const bounds = row.getBoundingClientRect();
                  return bounds.bottom > 80 && bounds.top < innerHeight;
                }) ?? null);
          reading.current = {
            element: anchor,
            top: anchor?.getBoundingClientRect().top ?? 0,
            y: scrollY,
            x: scrollX,
            left: table.current?.scrollLeft ?? 0,
            focus:
              document.activeElement instanceof HTMLElement
                ? document.activeElement
                : null,
          };
        }
        listener();
      }),
    [store],
  );
  const state = useSyncExternalStore(
    subscribe,
    store.getSnapshot,
    store.getServerSnapshot,
  );
  const result = isSearch
    ? (searched.result ?? failure(0, "SEARCH_NOT_STARTED"))
    : state.result;
  const range = isSearch
    ? (searched.applied ?? searched.failed ?? initialRange)
    : state.range;
  const successAt = isSearch ? searched.successAt : state.successAt;
  const busy = isSearch ? searched.busy : state.busy;
  const listError = isSearch ? searched.error : state.refreshError;
  const inputErrors = {
    ...(isSearch && searched.error ? fieldErrors(searched.error) : {}),
    ...validation,
  };
  useEffect(() => {
    if (mode === "live") {
      if (enteredSearch.current) store.startFresh();
      else store.start();
      return () => store.stop();
    }
    search.start();
    return () => search.stop();
  }, [mode, store, search]);
  useLayoutEffect(() => {
    const previous = reading.current;
    if (!previous) return;
    reading.current = null;
    if (table.current) table.current.scrollLeft = previous.left;
    const shift = previous.element?.isConnected
      ? previous.element.getBoundingClientRect().top - previous.top
      : 0;
    window.scrollTo(previous.x, previous.y + shift);
    if (previous.focus && !previous.focus.isConnected)
      listHeading.current?.focus({ preventScroll: true });
  }, [state]);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [wrap, setWrap] = useState(true);
  const [copyState, setCopyState] = useState("");
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
      detailController.current?.abort();
      detailVersion.current += 1;
    },
    [],
  );

  function changeMode(next: "live" | "search") {
    if (next === mode) return;
    detailController.current?.abort();
    detailVersion.current++;
    setSelection(null);
    setCopyState("");
    setValidation({});
    if (next === "search") {
      if (!enteredSearch.current) setInput(draft(recentRange()));
      enteredSearch.current = true;
      store.stop();
    } else search.stop();
    reading.current = null;
    setMode(next);
  }
  async function querySearch(query?: SearchQuery) {
    if (busy) return;
    const checked = query
      ? { ok: true as const, data: query }
      : fromDraft(input);
    if (!checked.ok) {
      const errors = fieldErrors(checked.problem);
      setValidation(errors);
      document.getElementById(`search-${Object.keys(errors)[0]}`)?.focus();
      return;
    }
    setValidation({});
    detailController.current?.abort();
    detailVersion.current++;
    setSelection((previous) =>
      previous && !previous.result
        ? {
            id: previous.id,
            result: failure(499, "DETAIL_REQUEST_INTERRUPTED"),
          }
        : previous,
    );
    if (await search.query(checked.data)) {
      const restore = panel.current?.contains(document.activeElement);
      setSelection(null);
      setCopyState("");
      if (restore) requestAnimationFrame(() => listHeading.current?.focus());
    }
  }
  async function queryList() {
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
    const restore = panel.current?.contains(document.activeElement);
    if (await store.refresh()) {
      setSelection(null);
      setCopyState("");
      if (restore) requestAnimationFrame(() => listHeading.current?.focus());
    }
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
    <div className="shell explorer-shell workspace-shell">
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
      <div className="workspace-body">
        <nav className="workspace-nav" aria-label="주 메뉴">
          <a href="#main-content" aria-current="page">
            <svg
              viewBox="0 0 20 20"
              width="18"
              height="18"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              aria-hidden="true"
            >
              <path d="M7 5h10M7 10h10M7 15h10M2 5h1M2 10h1M2 15h1" />
            </svg>
            로그 탐색
          </a>
        </nav>
        <div className="workspace-content">
          <main id="main-content" className="explorer-main" tabIndex={-1}>
            <div className="page-title">
              <div>
                <p className="eyebrow">서버 로그 관측</p>
                <h1>로그 탐색</h1>
                <p className="intro">
                  발생한 서버를 확인하고 로그의 상세 내용을 살펴보세요.
                </p>
              </div>
              {!isSearch && (
                <button
                  className="primary-button"
                  disabled={busy}
                  onClick={() => queryList()}
                >
                  {busy ? "조회 중…" : "새로고침"}
                </button>
              )}
            </div>
            <div className="view-switch" role="group" aria-label="보기 방식">
              <button
                aria-pressed={!isSearch}
                onClick={() => changeMode("live")}
              >
                실시간 보기
              </button>
              <button
                aria-pressed={isSearch}
                onClick={() => changeMode("search")}
              >
                검색 보기
              </button>
            </div>
            {isSearch && (
              <SearchForm
                value={input}
                onChange={(value) => {
                  setInput(value);
                  setValidation({});
                }}
                onSubmit={() => void querySearch()}
                onRecent={() => {
                  const recent = draft(recentRange());
                  setInput({ ...input, from: recent.from, to: recent.to });
                }}
                busy={busy}
                errors={inputErrors}
              />
            )}
            <div className="query-info">
              <div>
                <strong>
                  {isSearch
                    ? "검색 결과 · 최신순 · 최대 50건"
                    : "최근 24시간 · 최신순 · 최대 50건"}
                </strong>
                {!isSearch || searched.applied || searched.failed ? (
                  <>
                    <p>
                      조회 범위: <time>{displayTime(range.from)}</time> ~{" "}
                      <time>{displayTime(range.to)}</time>
                    </p>
                    <p>시작 시각 포함 · 종료 시각 제외</p>
                  </>
                ) : (
                  <p>검색을 실행하면 적용 조건과 조회 시간이 표시됩니다.</p>
                )}
                {isSearch && searched.applied && (
                  <p className="applied-conditions">
                    적용 조건: 서버 {searched.applied.instanceId ?? "전체"} ·
                    레벨 {searched.applied.level ?? "전체"} · 메시지{" "}
                    {searched.applied.keyword || "전체"}
                  </p>
                )}
              </div>
              <p>
                {successAt ? (
                  <>
                    {isSearch ? "마지막 검색 성공" : "마지막 목록 조회 성공"}
                    <br />
                    <time>{displayTime(successAt)}</time>
                  </>
                ) : isSearch ? (
                  "아직 성공한 검색이 없습니다"
                ) : (
                  "아직 성공한 조회가 없습니다"
                )}
              </p>
            </div>
            {!isSearch && (
              <div className="live-controls">
                <div className="live-state" role="status" aria-live="polite">
                  <strong>
                    {
                      {
                        waiting: "실시간 연결 대기",
                        connecting: "실시간 연결 중",
                        live: "실시간 연결됨",
                        reconnecting: "실시간 재연결 중",
                        error: "실시간 연결 실패",
                        "needs-refresh": "새 목록 조회 필요",
                        paused: "실시간 일시정지",
                      }[state.phase]
                    }
                  </strong>
                  <p>
                    {state.pausedAt
                      ? `정지 시각: ${displayTime(state.pausedAt)}`
                      : state.phase === "needs-refresh"
                        ? "기존 결과를 보존했습니다. 이어받기 요청을 계속할 수 없어 새로고침으로 최신 목록을 조회해야 합니다."
                        : state.phase === "error"
                          ? "기존 결과를 보존하고 다시 연결합니다. 연결 중단이 로그 저장 중단이나 유실을 뜻하지는 않습니다."
                          : state.phase === "waiting"
                            ? "목록 조회에 성공하면 실시간 연결을 시작합니다."
                            : state.phase === "connecting"
                              ? "마지막 처리 위치에서 실시간 연결을 시작하고 있습니다."
                              : state.phase === "reconnecting"
                                ? "연결을 갱신하고 마지막 처리 위치부터 이어받습니다."
                                : "새 로그가 없어도 연결은 유지됩니다. 최근 24시간 범위는 자동으로 이동합니다."}
                  </p>
                  <p>
                    마지막 정상 실시간 로그 수신:{" "}
                    {state.liveAt
                      ? displayTime(state.liveAt)
                      : "아직 수신한 로그가 없습니다"}
                  </p>
                  <p>
                    실시간으로 추가된 로그 {state.newCount}건 · 목록은 최신
                    50건까지 표시
                  </p>
                </div>
                <button
                  disabled={busy}
                  onClick={state.paused ? store.resume : store.pause}
                >
                  {state.paused ? "실시간 재개" : "일시정지"}
                </button>
              </div>
            )}
            {isSearch && (
              <div className="search-status" role="status" aria-live="polite">
                <p>
                  검색 결과는 조회 시점의 조건으로 고정됩니다. 자동 갱신하지
                  않으며, 최신 로그는 실시간 보기에서 확인할 수 있습니다.
                </p>
                {searched.result?.ok && (
                  <>
                    {searched.result.data.synchronization.indexingPending ? (
                      <p>
                        검색 반영 대기 중입니다. 전체 색인 대기{" "}
                        {searched.result.data.synchronization.pendingCount}건 ·
                        재시도{" "}
                        {searched.result.data.synchronization.retryingCount}건.
                        이 수는 현재 검색의 누락 건수가 아닙니다.
                      </p>
                    ) : (
                      <p>
                        조회 시점에 관측한 색인 대기 작업이 없습니다. 이후 검색
                        결과는 달라질 수 있습니다.
                      </p>
                    )}
                    <button
                      aria-disabled={busy}
                      onClick={() => void querySearch(searched.applied!)}
                    >
                      적용 조건으로 다시 조회
                    </button>
                  </>
                )}
              </div>
            )}
            {result.ok && result.data.truncated && (
              <p className="limit-notice">
                최신 50건만 표시합니다. 화면에서 빠진 로그를 포함한 전체 이력
                보기를 뜻하지 않습니다.
              </p>
            )}
            <p className="query-status" role="status" aria-live="polite">
              {busy
                ? isSearch
                  ? "검색 중입니다. 이전 결과가 있다면 이전 조건의 결과입니다."
                  : "목록을 갱신하고 있습니다. 기존 결과는 이전 조회 결과입니다."
                : result.ok
                  ? `현재 목록 ${result.data.items.length}건`
                  : isSearch
                    ? searched.error
                      ? "검색 실패"
                      : "검색 조건을 설정하고 조회해 주세요."
                    : "목록 조회 실패"}
            </p>
            {listError && (
              <div className="error-notice" role="alert">
                <strong>
                  {isSearch
                    ? searchGuidance(listError.code)
                    : guidance(listError.code)}
                </strong>
                {isSearch && searched.failed && (
                  <p>
                    실패한 검색: {displayTime(searched.failed.from)} ~{" "}
                    {displayTime(searched.failed.to)} · 서버{" "}
                    {searched.failed.instanceId ?? "전체"} · 레벨{" "}
                    {searched.failed.level ?? "전체"} · 메시지{" "}
                    {searched.failed.keyword || "전체"}
                  </p>
                )}
                {result.ok && <p>이전 조회 결과를 표시하고 있습니다.</p>}
                {listError.retryAfter && (
                  <p>
                    서버가 재시도 대기를 요청했습니다. 잠시 후 다시 시도해
                    주세요.
                  </p>
                )}
                <button
                  disabled={busy}
                  onClick={() =>
                    isSearch
                      ? void querySearch(searched.failed!)
                      : void queryList()
                  }
                >
                  {isSearch ? "같은 조건으로 다시 시도" : "목록 다시 조회"}
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
                    <h3>
                      {isSearch
                        ? "현재 검색 결과가 없습니다"
                        : "조회 범위에 로그가 없습니다"}
                    </h3>
                    <p>
                      {isSearch
                        ? "조건을 바꾸거나 색인 반영 후 같은 조건으로 다시 조회할 수 있습니다."
                        : "최근 24시간에 발생한 로그만 표시합니다. 실시간 연결 중이면 새 로그를 기다립니다."}
                    </p>
                  </div>
                )}
                {result.ok && result.data.items.length > 0 && (
                  <div
                    ref={table}
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
                            data-event-id={item.eventId}
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
                                <span className="exception-label">
                                  예외 있음
                                </span>
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
                    {isSearch
                      ? "조건을 설정하고 조회하면 검색 결과가 여기에 표시됩니다."
                      : "조회에 성공하면 로그가 여기에 표시됩니다."}
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
              페이지 탐색은 다음 단계에서 제공됩니다. 검색은 메시지를 단어로
              분석하며 예외 원문은 상세에서 확인합니다.
            </p>
          </main>
          <footer>
            logscope<span>서버와 원인을 한곳에서</span>
          </footer>
        </div>
      </div>
    </div>
  );
}
