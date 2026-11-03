# 실시간 로그와 SSE

2026-11-03 기능별 문서 안내. 기존 원본을 옮기거나 복사하지 않고 필요한 부분으로 연결한다.

## 먼저 이해할 흐름

처음 받은 목록과 같은 기준점(cursor)부터 연결해 그 사이 저장된 로그도 이어받는다. SSE는 서버가 브라우저에 보내는 단방향 전달이며 폴링과 구분한다.

## 왜 이렇게 구성했나

로그 발생시각은 늦게 도착할 수 있으므로 복구는 DB 저장 확정 순서로 한다. 프론트는 이벤트를 정상 처리한 뒤 cursor를 보관하고 재연결/재개에 사용한다. ready만으로 기준점을 앞당기지 않는다. 최신 50건 화면과 모든 이력의 보존은 다르다.

## 읽을 순서

1. [폴링·SSE·WebSocket 선택 이유](../../decisions/sse-log-delivery.md)
2. [API·저장 순서·DB replay 명세](../../contracts/log-sse.md)
3. [브라우저 처리·시간·정지·복구](../../frontend-live-view.md)
4. [백엔드 학습 기록](../../learning/backend.md#backend-sse-replay-20261028)
5. [프론트 학습 기록](../../learning/frontend.md#frontend-sse-20261028)

원본의 날짜와 후속 기록을 확인한다. 옛 제안·현재 구현·측정된 결과는 구분해서 읽는다.

[전체 문서 안내](../../README.md) · [현재 상태](../../implementation-status.md) · [다음 계획](../../next-steps.md)
