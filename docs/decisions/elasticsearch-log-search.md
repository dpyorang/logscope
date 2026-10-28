# Elasticsearch 로그 검색과 MySQL 원본 유지

2026-10-28 사용자 승인으로 Redis를 제외하고 Elasticsearch 검색을 구현한다. [검색 명세](../contracts/log-search.md)를 교환 기준으로 사용한다.

MySQL FULLTEXT도 역색인이지만 사용자가 한국어 분석·전문 검색과 검색엔진 동작을 직접 배우는 것을 목표로 했다. 로그 메시지 검색이 핵심인 logscope에서 Nori 분석과 정확한 keyword 필터를 분리하고 검색 부하를 원본 DB와 나누기 위해 ES를 선택했다. ES가 언제나 더 빠르다는 측정 결과나 적은 데이터라는 가정에 근거한 결정은 아니다. Redis 캐시는 반복 조회 병목이 확인되지 않았고 갱신 정합성 비용이 추가돼 도입하지 않는다.

역색인은 단어→문서 ID 목록을 미리 만드는 구조다. keyword 필드는 레벨/서버를 정확히 고르고, text 필드는 Nori가 만든 토큰으로 message를 찾는다. AND match는 토큰을 모두 포함하는 검색이며 문자열 그대로 포함이나 의미/동의어 자동 추론과 다르다. 발생시각/eventId 최신순은 로그 조사 흐름을 유지하기 위한 선택이다.

MySQL을 원본으로 유지하고 ES는 재생성 가능한 검색 projection으로 둔다. 원본 저장과 ES HTTP 호출을 한 요청에서 각각 성공했다고 가정하지 않는다. MySQL 트랜잭션에 outbox를 함께 기록해 프로세스가 중단돼도 미처리 작업이 남고, 외부 색인 확인 후 작업을 제거한다. 동일 eventId를 ES document ID로 사용해 at-least-once 재전송이 문서 중복을 만들지 않게 한다. ES 장애는 수집 실패로 전파하지 않는다.

ES는 refresh 전 문서가 검색되지 않는 준실시간 엔진이다. bulk HTTP200만 믿지 않고 각 항목 상태와 검색 가능 반영, 색인 세대를 확인한다. DB commit 실패 후 재전송, 부분 거절, 삭제된 색인 backfill을 별도로 검증한다. 다른 DB의 색인이나 다른 매핑을 자동 삭제해 해결하지 않는다.

추가 ES Java SDK·Spring Data 의존성 대신 버전을 고정한 REST 계약과 JDK HTTP 클라이언트를 사용한다. 작은 표면의 매핑/색인/검색만 사용하고 응답 byte·기한·동시성을 제한한다. 별도 ES 서버와 원본 동기화 운영 비용은 생긴다. 현재 검색은 최신50 기본·100 최대와 잘림 표시까지이며 과거 페이지 커서는 기존 승인 설계를 별도 기능으로 구현한다. 단일 노드·replica0 개발 구성은 운영 가용성이나 높은 처리량을 보장하지 않는다.

참고: [Elastic9.5.5 릴리스](https://www.elastic.co/docs/release-notes/elasticsearch), [Nori](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori), [refresh](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter), [Bulk API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk).
