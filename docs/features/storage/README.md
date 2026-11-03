# 저장·중복·트랜잭션

2026-11-03 기능별 문서 안내. 기존 원본을 옮기거나 복사하지 않고 필요한 부분으로 연결한다.

## 먼저 이해할 흐름

MySQL은 로그의 원본이다. 한 로그의 원본·SSE 저장 순서·검색 대기열을 함께 확정하거나 모두 취소한다. 실제 ES 전송과 SSE 네트워크 전송은 DB 트랜잭션 밖이다.

## 왜 이렇게 구성했나

eventId와 내용을 비교해 같은 로그 재전송은 중복, 같은 ID의 다른 내용은 충돌로 처리한다. 명시적 JDBC INSERT와 트랜잭션을 사용한 이유는 저장/충돌 경쟁을 직접 제어하기 위해서다. JPA 의존성 유지와 실제 저장 경로는 구분한다. Flyway는 이미 적용된 DB 파일을 고치는 대신 새 migration으로 구조를 변경한다.

## 읽을 순서

1. [첫 저장·동시 중복 처리 선택 이유](../../learning/backend.md#backend-ingest-read-20261027)
2. [입력·저장·오류 명세](../../contracts/log-api-draft.md)
3. [색인 작업도 같은 트랜잭션에 넣는 이유](../../decisions/elasticsearch-log-search.md)
4. [DB migration 원본](../../../src/main/resources/db/migration/)

원본의 날짜와 후속 기록을 확인한다. 옛 제안·현재 구현·측정된 결과는 구분해서 읽는다.

[전체 문서 안내](../../README.md) · [현재 상태](../../implementation-status.md) · [다음 계획](../../next-steps.md)
