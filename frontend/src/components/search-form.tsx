"use client";
import type { SearchDraft } from "@/lib/search";
import { LEVELS, SERVER_OPTIONS } from "@/lib/search";

export function SearchForm({
  value,
  onChange,
  onSubmit,
  onRecent,
  busy,
  errors,
}: {
  value: SearchDraft;
  onChange: (value: SearchDraft) => void;
  onSubmit: () => void;
  onRecent: () => void;
  busy: boolean;
  errors: Record<string, string>;
}) {
  const field = (key: keyof SearchDraft) => ({
    id: `search-${key}`,
    value: value[key],
    onChange: (
      event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>,
    ) => onChange({ ...value, [key]: event.target.value }),
    "aria-invalid": !!errors[key],
    "aria-describedby": errors[key] ? `search-${key}-error` : undefined,
  });
  const error = (key: string) =>
    errors[key] ? (
      <span className="field-error" id={`search-${key}-error`}>
        {errors[key]}
      </span>
    ) : null;
  return (
    <form
      className="search-form"
      aria-label="로그 검색 조건"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <div className="search-fields">
        <div>
          <label htmlFor="search-from">시작 시각 (UTC)</label>
          <input
            {...field("from")}
            type="datetime-local"
            step="0.001"
            required
          />
          {error("from")}
        </div>
        <div>
          <label htmlFor="search-to">종료 시각 (UTC)</label>
          <input {...field("to")} type="datetime-local" step="0.001" required />
          {error("to")}
        </div>
        <div>
          <label htmlFor="search-instanceId">서버</label>
          <select {...field("instanceId")}>
            <option value="">전체 서버</option>
            {SERVER_OPTIONS.map((server) => (
              <option key={server} value={server}>
                {server}
              </option>
            ))}
          </select>
          {error("instanceId")}
        </div>
        <div>
          <label htmlFor="search-level">레벨</label>
          <select {...field("level")}>
            <option value="">전체 레벨</option>
            {LEVELS.map((level) => (
              <option key={level} value={level}>
                {level}
              </option>
            ))}
          </select>
          {error("level")}
        </div>
        <div className="keyword-field">
          <label htmlFor="search-keyword">메시지 검색어</label>
          <input
            {...field("keyword")}
            type="text"
            autoComplete="off"
            aria-describedby={
              errors.keyword
                ? "search-keyword-error search-help"
                : "search-help"
            }
          />
          {error("keyword")}
        </div>
      </div>
      <p id="search-help" className="search-help">
        기간은 최대 24시간입니다. 메시지는 200자까지 단어 분석 후 AND
        검색합니다. 단순 부분 문자열·구문 검색이나 예외 원문 검색은 제공하지
        않습니다.
      </p>
      {Object.keys(errors).length > 0 && (
        <p className="field-error" role="alert">
          표시된 검색 조건을 확인해 주세요.
        </p>
      )}
      <div className="search-actions">
        <button type="submit" className="primary-button" disabled={busy}>
          {busy ? "검색 중…" : "조회"}
        </button>
        <button type="button" onClick={onRecent}>
          기간을 최근 24시간으로 설정
        </button>
        <span>조건을 편집한 뒤 조회 또는 Enter로 적용합니다.</span>
      </div>
    </form>
  );
}
