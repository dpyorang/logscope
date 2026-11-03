# 화면·SSR·디자인

2026-11-03 기능별 문서 안내. 기존 원본을 옮기거나 복사하지 않고 필요한 부분으로 연결한다.

## 먼저 이해할 흐름

Next.js는 최초 화면을 서버에서 렌더링하고 브라우저의 후속 API를 중계한다. Spring Boot의 저장·검색 로직을 화면에서 다시 구현하지 않는다.

## 왜 이렇게 구성했나

검색은 ES 분석 결과이고 SSE는 모든 신규 로그 스트림이라 두 결과를 문자열 포함 검사로 합치지 않는다. 검색 모드에서 SSE를 닫고 실시간으로 돌아오면 새 snapshot을 받는다. 상세는 MySQL 원문을 읽는다. API 주소는 서버에 두고 오류 때 기존 결과와 상세를 보존한다.

## 읽을 순서

1. [현재 실시간 동작](../../frontend-live-view.md)
2. [현재 검색·모드·오류 처리](../../frontend-search-view.md)
3. [Next.js 실행·검사·환경 선택](../../frontend-development.md)
4. [화면 시안·후속 적용 기준](../../design/log-explorer-draft.md)
5. [프론트 시간순 학습](../../learning/frontend.md)
6. [디자인 시간순 학습](../../learning/design.md)

원본의 날짜와 후속 기록을 확인한다. 옛 제안·현재 구현·측정된 결과는 구분해서 읽는다.

[전체 문서 안내](../../README.md) · [현재 상태](../../implementation-status.md) · [다음 계획](../../next-steps.md)
