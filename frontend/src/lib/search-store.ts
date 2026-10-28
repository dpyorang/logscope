import { type Problem, type Result } from "./logs";
import {
  parameters,
  searchResult,
  SEARCH_BROWSER_TIMEOUT,
  type SearchQuery,
  type SearchResult,
} from "./search";
import { read } from "./live-store";
export type SearchState = {
  result: Result<SearchResult> | null;
  applied: SearchQuery | null;
  failed: SearchQuery | null;
  error: Problem | null;
  successAt: string | null;
  busy: boolean;
};
export class SearchStore {
  private initial: SearchState = {
    result: null,
    applied: null,
    failed: null,
    error: null,
    successAt: null,
    busy: false,
  };
  private state = this.initial;
  private listeners = new Set<() => void>();
  private active = false;
  private controller: AbortController | null = null;
  private version = 0;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  getSnapshot = () => this.state;
  getServerSnapshot = () => this.initial;
  private update(patch: Partial<SearchState>) {
    this.state = { ...this.state, ...patch };
    for (const listener of this.listeners) listener();
  }
  start() {
    this.active = true;
  }
  stop() {
    this.active = false;
    this.version++;
    this.controller?.abort();
    this.controller = null;
    if (this.state.busy) this.update({ busy: false });
  }
  async query(query: SearchQuery) {
    if (!this.active || this.state.busy) return false;
    this.controller?.abort();
    const controller = new AbortController();
    this.controller = controller;
    const version = ++this.version;
    this.update({ busy: true });
    const timer = setTimeout(() => controller.abort(), SEARCH_BROWSER_TIMEOUT);
    const result = await read(
      `/api/logs/search?${parameters(query)}`,
      (value): value is SearchResult => searchResult(value, query),
      controller.signal,
    );
    clearTimeout(timer);
    if (!this.active || this.version !== version) return false;
    this.controller = null;
    if (result.ok)
      this.update({
        result,
        applied: { ...query },
        failed: null,
        error: null,
        successAt: new Date().toISOString(),
        busy: false,
      });
    else
      this.update({
        failed: { ...query },
        error: {
          ...result.problem,
          code:
            result.problem.code === "REQUEST_CANCELLED"
              ? "UPSTREAM_TIMEOUT"
              : result.problem.code,
        },
        busy: false,
      });
    return result.ok;
  }
}
