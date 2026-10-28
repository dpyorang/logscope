# Elasticsearch 로그 검색 명세

2026-10-28 사용자가 Redis를 제외하고 Elasticsearch 기반 레벨·서버·기간·메시지 검색과 MySQL 원본/색인 동기화를 요청했다. 기존 정적 목록·상세·SSE API는 유지하고 검색 전용 API를 추가한다. 프론트 검색 화면·과거 페이지 커서·집계·트래픽은 이 백엔드 작업의 구현 범위가 아니다.

## 검색 API

`GET /api/v1/logs/search?from=2026-10-28T00%3A00%3A00.000Z&to=2026-10-29T00%3A00%3A00.000Z&level=ERROR&instanceId=was-2&keyword=결제%20오류&size=50`

- from/to 필수. 기존 UTC 입력 규칙(초 또는 밀리초1~3자리), 시작 포함·끝 제외, from < to, 최대24시간. ES에는 UTC 밀리초로 정규화한다. 보존 기간을 뜻하지 않는다.
- level 선택: TRACE/DEBUG/INFO/WARN/ERROR 단일 값, 대소문자 구분. instanceId 선택: 기존 `[a-z0-9][a-z0-9._-]{0,63}` 패턴의 단일 정확한 값. 서로 다른 조건은 AND다.
- keyword 선택: 앞뒤 공백 제거 후1~200 Unicode code point. message만 Nori 분석 후 AND match로 검색한다. 영어는 lowercase 분석, 한국어 복합어는 mixed 분해를 사용한다. 단어 순서·연속 문자열·대소문자 구분 리터럴 포함 검색을 보장하지 않는다. `%`, `_`, `*`, 따옴표와 OR 등을 SQL/Query DSL 문법으로 해석하지 않고 분석기 입력으로만 취급한다. 자동 동의어·오타 교정·exception 원문 검색은 제공하지 않는다.
- size 기본50,1~100. 응답은 발생시각/eventId 내림차순이며 관련도 점수로 정렬하지 않는다. 빈값·중복 인자·알 수 없는 인자는400 VALIDATION_ERROR다. cursor/page/sort 인자는 받지 않는다.
- 검색 결과는 최신 size건이며 더 있으면 truncated=true. 전체 건수나 페이지 끝으로 오해하지 않게 한다. 과거 페이지 이동은 후속 구현이며 기존 `/api/v1/logs`의 필터/커서 미구현501 규칙은 변경하지 않는다. 이 검색 API를 추가한 것은 그 정적 API를 몰래 ES로 바꾸기 위한 것이 아니다.

```json
{"items":[],"size":50,"truncated":false,"synchronization":{"pendingCount":0,"retryingCount":0,"indexingPending":false}}
```

items는 기존 Summary(eventId/occurredAt/serviceName/instanceId/level/message/hasException)이며 원문 message를 자르지 않는다. exception 원문은 MySQL 상세 API에서 읽는다. synchronization은 전체 색인 대기열의 관측값이며 해당 검색의 매칭 건수나 원본과 검색의 완전한 snapshot을 뜻하지 않는다. pendingCount는 아직 검색 가능 반영을 확인하지 못한 작업 수, retryingCount는 그중 bulk 전송 실패가 기록된 작업 수다. indexingPending=true이면 현재 결과가 추가 색인으로 달라질 수 있음을 안내한다. 최초 backfill 중에도 일부 결과를 반환할 수 있으나 완료로 표시하지 않는다. 새 로그가 관측 이후 저장되거나 늦게 도착할 수 있어 pendingCount=0이 영구적인 검색 완전성을 보장하지 않는다.

성공은200 application/json, Cache-Control:no-store다. ES 비활성·네트워크·잘못된 응답·부분 shard 실패·ES timeout은503 SEARCH_UNAVAILABLE, 색인 세대 불일치는503 SEARCH_INDEX_REBUILDING, 서버당16개 동시 검색 상한은503 SEARCH_CAPACITY_EXCEEDED다. 장애를 빈 목록으로 대체하거나 MySQL LIKE로 조용히 fallback하지 않는다. DB 장애는 기존503 STORAGE_UNAVAILABLE다. 오류에 원문 검색어·로그·ES 응답·접속 정보를 반사하지 않는다.

## 색인과 동기화

- Elasticsearch9.5.5 공식 이미지에 같은 버전 analysis-nori를 설치한다. 기본 색인은 logscope-logs-v1, 단일 shard·replica0·refresh1초의 격리 개발 구성이다. message는 Nori tokenizer(mixed)+lowercase, ID/서버/서비스/레벨은 keyword, 시각은 strict_date_time, 예외 여부는 boolean이다. dynamic strict로 필드 증식을 막고 ES document `_id`는 원본 eventId로 고정한다.
- V4 migration은 MySQL outbox와 색인 상태 테이블을 추가하고 기존 로그를 대기열에 채운다. 신규 로그·SSE 순서·색인 작업은 한 트랜잭션에서 저장한다. ES가 중단돼도 MySQL 수집과 SSE는 계속 작동한다. 중복 수집은 새 작업을 만들지 않는다.
- 매 작업은 최대50건이며 상태 행 FOR UPDATE로 작업자 간 중복 소비와 재구성을 직렬화한다. 외부 ES 요청 동안 잡는 잠금은 검색 상태 행이며 로그의 저장 순서 행을 잠그지 않는다. 매1초 fixed-delay로 작업하고 bulk 항목별 성공 여부를 검증한다. 일부 거절이면 성공한 항목만 제거한다. `refresh=wait_for`로 검색 가능 반영까지 확인 후 outbox를 제거한다.
- 실패 항목은 MySQL에 남기고1·2·4초 등 최대60초 간격으로 계속 재시도한다. 횟수는30에서 포화시키며 작업을 조용히 포기하지 않는다. ES 생성/연결 실패는 작업자 전체를5초 늦춘다. 영구 오류는 설정/매핑을 고치기 전까지 계속 대기하며 retryingCount로 드러낸다. DB commit 실패로 이미 색인한 항목을 다시 보내도 동일 `_id`로 중복 문서를 만들지 않는다.
- 색인 `_meta`에 형식 버전과 MySQL source epoch를 기록한다. 다른 DB 소유나 호환되지 않는 매핑의 색인을 덮어쓰지 않는다. 실제 index UUID가 바뀌면 기존 원본 전부를 다시 대기열에 넣는다. 색인 유실 후 재생성도 같은 경로다. 자동 색인 생성은 ES에서 끄고 앱의 명시적 매핑 생성만 사용한다. 요청 중 색인이 교체되는 경쟁도 UUID 재확인으로 탐지한다.
- ES REST는 서버 설정 URL만 사용하고 자격정보 포함 URL·다른 path·redirect를 허용하지 않는다. 요청당 연결1초·요청3초·본문 완료 포함4초 기한, 응답4MiB, bulk 요청2MiB·50건 한도를 둔다. summary만 색인해 stack trace 원문을 복제하지 않는다. 기한·한도는 개발 시작값이며 부하 성능 보장이 아니다.

## 실행과 경계

Compose의 app은 SEARCH_ENABLED=true, SEARCH_URL=http://elasticsearch:9200으로 연결한다. ES 포트는 호스트에 공개하지 않는다. MySQL과 ES는 별도 named volume이다. ES가 내려가도 app의 DB 기반 readiness와 수집을 유지하며 검색만503과 대기 상태로 처리한다. Compose 밖의 기본은 SEARCH_ENABLED=false이며 대기열은 MySQL에 보존돼 나중에 활성화할 수 있다.

보안 비활성 ES는 외부 공개하지 않는 합성 데이터 개발 환경 전용이다. 인증·TLS·운영 replica·샤드/디스크 용량·보존/삭제·alias 기반 무중단 재색인은 운영 설계와 승인 후 진행한다. 원본 직접 SQL 수정/삭제나 snapshot 복원 이후 자동 정합성은 보장하지 않는다. 같은 index UUID를 유지한 개별 ES 문서의 외부 수정/삭제도 자동 감지하지 않는다. 현재 삭제 API가 없으며 로그 원문은 불변으로 취급한다. 색인 대기열은 디스크의 영속 작업이며 장기 장애의 저장 공간 한도가 따로 생기는 점을 운영 설계에서 다뤄야 한다.

검색 가능 시점은 MySQL 저장/SSE 수신보다 늦다. 색인 직후 refresh 대기와 backfill/재시도 대기가 포함된다. 검색과 SSE를 같은 읽기 snapshot으로 주장하지 않는다. 기존 message 리터럴 부분 문자열 명세는 기존 API의 이전/후속 페이지 설계 기록으로 보존하며, 이 검색 API에서는 분석 기반 AND match를 새 기준으로 사용한다. 프론트 연동은 별도 작업이다.
