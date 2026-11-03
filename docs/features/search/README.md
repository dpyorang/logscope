# 메시지 검색과 Elasticsearch

2026-11-03 기능별 문서 안내. 기존 원본을 옮기거나 복사하지 않고 필요한 부분으로 연결한다.

## 먼저 이해할 흐름

MySQL은 원본, Elasticsearch는 검색용 사본이다. Nori로 한국어 메시지를 분석하고 기간·서버·레벨 조건과 함께 검색한다.

## 왜 이렇게 구성했나

Outbox는 ES에 보낼 할 일을 MySQL에 기록한 테이블이다. 작업자가 반영하고 검색 가능함을 확인한 뒤 제거한다. 실패는 재시도하고 같은 eventId로 ES 중복 문서를 막는다. Redis는 반복 조회 병목이 확인되지 않았고 갱신 정합성 비용이 있어 도입하지 않았다. ES가 무조건 빠르다는 성과를 측정한 것은 아니다.

## 읽을 순서

1. [ES·MySQL FULLTEXT 대안과 Redis 제외 이유](../../decisions/elasticsearch-log-search.md)
2. [검색/API/색인 지연·복구 명세](../../contracts/log-search.md)
3. [실시간과 분리한 검색 화면](../../frontend-search-view.md)
4. [백엔드 ES·Outbox 학습](../../learning/backend.md#backend-elasticsearch-search-20261028)
5. [프론트 검색·필터 학습](../../learning/frontend.md#frontend-search-20261028)

원본의 날짜와 후속 기록을 확인한다. 옛 제안·현재 구현·측정된 결과는 구분해서 읽는다.

[전체 문서 안내](../../README.md) · [현재 상태](../../implementation-status.md) · [다음 계획](../../next-steps.md)
