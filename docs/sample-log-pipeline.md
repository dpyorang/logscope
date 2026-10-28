# 샘플 WAS 파일 로그와 Vector 수집

2026-10-27 사용자 요청으로 샘플 WAS 두 대를 구현한다. 각 WAS의 Logback 파일을 별도 Vector가 읽어 logscope 배치 API로 보낸다. Kafka는 후속 검토 대상으로 둔다. 이전 WAS 내부 HTTP 대기열 설계 대신 파일과 수집기의 책임을 분리한다. 최종 세 대 확장 목표는 보존하며 이번 실행 범위는 was-1·was-2다.

## 실행과 확인

기존 로컬 환경 안내에 따라 실제 비밀은 Git에서 제외한 `.env`에 준비한다. `.env.example`의 값은 합성 예시다.

```sh
docker compose --profile samples up --build -d
```

- logscope 화면: http://localhost:3000
- was-1 수동 생성 화면: http://localhost:8081
- was-2 수동 생성 화면: http://localhost:8082

WAS마다 시작 30초 후부터 30초 간격으로 TRACE·DEBUG·INFO·WARN·ERROR 중 한 레벨의 합성 로그를 만든다. 수동 생성은 레벨 지정·랜덤·ERROR 예외 생성을 제공하며 자동 생성은 일시 정지할 수 있다. 정지 상태는 메모리에만 두며 재시작하면 켜진다. 브라우저의 최근 항목은 해당 페이지에서 누른 수동 요청 20건이다. 중앙 수집 완료 목록은 logscope에서 확인한다.

`LOG_GENERATED` 응답은 Logback 로깅 호출 완료를 뜻한다. 디스크 fsync나 중앙 저장 성공 확인이 아니며 Logback 쓰기 오류는 WAS 진단 콘솔에서 확인한다. 파일은 즉시 flush하지만 장비·디스크 장애의 무손실을 보장하지 않는다. 생성 화면의 eventId와 logscope 상세 `/api/v1/logs/{eventId}`로 같은 이벤트를 대조할 수 있다.

## 파일과 수집기

전용 `SAMPLE_EVENTS` logger만 JSON Lines로 `/logs/events.jsonl`에 기록한다. 서비스 자체 진단과 수집기 진단은 파일에 넣지 않아 순환 수집을 막는다. TRACE를 포함한 다섯 레벨을 전용 logger에서 허용한다. eventId는 생성 시 한 번 발급한 UUIDv4, occurredAt은 UTC 밀리초, serviceName은 sample-was, instanceId는 was-1·was-2다. 예외는 줄바꿈이 escape된 하나의 JSON 행이며 중앙 상세에서는 실제 줄바꿈으로 복원한다.

파일은 날짜·10MB 기준으로 회전하고 2일 이력·총 50MB 상한을 둔다. 합성 샘플의 로컬 디스크 사용 제한이며 로그 보존 서비스 정책이 아니다. 테스트에서만 `SAMPLE_LOG_MAX_SIZE=1KB`로 회전을 빠르게 확인한다. `/logs`는 WAS별 별도 named volume이고 Vector에는 읽기 전용으로 연결한다. Vector 체크포인트도 별도 named volume에 둔다. `docker compose down`은 보존하지만 `down --volumes`는 로그·체크포인트·DB까지 지우므로 일반 정리 명령으로 사용하지 않는다.

Vector 0.60.0은 현재 파일과 회전 파일을 읽고 inode/device fingerprint와 체크포인트를 사용한다. HTTP sink acknowledgements를 켜서 전달 확인을 파일 source까지 연결한다. 최초 파일은 처음부터 읽고 재시작 시 기록된 위치부터 이어간다. 파일 회전·보존 삭제, 디스크 고장, 강제 종료 사이의 모든 누락 가능성을 제거하지 않는다. 재전달은 eventId로 중복을 판정한다. Vector 재시작과 WAS 재시작은 서로 독립적이다.

| 항목 | 설정과 이유 |
| --- | --- |
| 배치 | 최대 100건, 직렬화 전 이벤트 크기 합계 65,536 bytes 또는 대기 2초. 낮은 발생량에도 오래 대기하지 않으면서 요청 수를 줄인다 |
| 동시 요청 | WAS별 Vector HTTP sink 1개·동시 1건. 작은 데모의 순서와 부하를 단순화한다 |
| HTTP 기한·재시도 | 시도 기한 3초, 최대 재시도 2회. Vector HTTP sink의 backoff·재시도 상태 규칙을 따르며 이전 자체 전송기 jitter·Retry-After 상한을 구현한 것으로 보지 않는다 |
| 메모리 버퍼 | 최대 100건, 가득 차면 block. 낮은 합성 발생량의 메모리 점유를 제한한다. 원본 파일·전송 확인 후 체크포인트를 함께 사용한다 |
| 개별 행 | 최대 256KiB. 이번 생성기는 짧은 이커머스 합성 메시지와 시나리오별 예외만 만들며 범용 파일 수집기는 아니다 |

`max_bytes`는 최종 HTTP JSON byte 상한이 아니다. JSON escape와 framing으로 실제 요청은 커질 수 있다. 이 샘플의 짧고 고정된 이벤트는 서버의 1MiB 상한 안에서 검증하며, 임의 외부 로그·큰 예외를 도입할 때 별도 크기 설계가 필요하다. 유한 재시도 소진·영구 HTTP 오류·잘못된 JSON 변환은 버려질 수 있다. 버퍼가 block되어도 파일 보존 삭제를 막지는 않으므로 장기 장애의 무손실 수집을 보장하지 않는다. 진단은 `docker compose logs vector-1 vector-2 app`에서 확인한다. 별도 운영 지표·DLQ·Kafka·인증·외부 공개·트래픽 관리 설계는 이번 범위 밖이다.

## 디스크 버퍼 대신 메모리 버퍼를 선택한 이유

최초 검증에서는 Vector 0.60.0 디스크 버퍼를 시도했지만, 고립된 새 이벤트가 파일에서 읽혀 디스크 버퍼에 존재하는데 HTTP sink는 읽지 않는 정체를 반복 재현했다. [공식 이슈 #26523](https://github.com/vectordotdev/vector/issues/26523)에도 0.60.0 저빈도 HTTP sink의 같은 증상이 보고돼 있다. 정확한 내부 원인을 자체 패치로 확정한 것은 아니며 샘플에서 문제 경로를 사용하지 않기로 했다. 임의 keepalive 로그를 넣어 정체를 가리거나 미출시 소스 패치를 배포하지 않는다.

현재는 100건 메모리 버퍼와 HTTP sink의 end-to-end acknowledgements를 사용한다. 체크포인트 갱신은 단순 파일 읽기와 구별하며 전송 확인을 source까지 연결한다. Vector 강제 종료 시 메모리 이벤트는 없어질 수 있고, 남아 있는 원본 파일과 마지막 체크포인트로 다시 읽을 수 있으나 모든 종료·파일 삭제·재시도 소진 상황의 복구는 보장하지 않는다. 디스크 버퍼는 공식 수정 릴리스의 저빈도·재시작 검증을 통과한 뒤 재검토한다.

## 배치 API와 부분 실패

`POST /api/v1/logs/batch`는 기존 단건 API를 유지하면서 추가한다. [API 명세](contracts/log-api-draft.md)의 후속 배치 항목을 따른다. 잘못된 한 이벤트가 정상 이벤트 전송을 계속 막지 않도록 항목별 거절을 사용한다. Vector는 성공 HTTP 응답의 거절 목록을 개별 재처리하지 않으므로 거절은 전달 포기다. 서버는 거절 건수만 WARN으로 기록하고 본문·개인 데이터를 반사하지 않는다. 임의 데이터 운영 수집 전에는 격리·재처리 정책이 필요하다.

DB 장애는 503으로 응답하며 앞선 항목은 이미 저장됐을 수 있다. 전체 배치 재시도에서 eventId·원문은 그대로 유지하고 기존 저장 내용과 같으면 DUPLICATE로 처리한다. 배치 전체 원자성을 약속하지 않는다. 요청이 다른 경우 같은 eventId는 충돌로 거절한다.

## 검증

`./scripts/verify.sh`는 기존 단위·실제 MySQL/HTTP·프론트·브라우저·이미지 검증을 유지하며 샘플 단위 테스트, samples Compose 설정, 샘플 이미지 빌드, Vector 설정 검사와 격리된 전체 파이프라인 검증을 추가한다.

검증 실행마다 백엔드·프론트·샘플 이미지에 고유 태그를 붙이고 같은 실행의 테스트에 전달한다. 다른 worktree에서 전체 검증을 실행해도 고정 태그를 덮어써 이전 코드로 검사하는 일을 방지한다. 종료 시 자기 이미지 태그만 제거한다.

파이프라인 테스트는 임의 이름의 네트워크·DB·로그/체크포인트 볼륨만 만들고 종료 시 자기 자원만 제거한다. 실제 브라우저에서 양쪽 수동 예외 버튼을 누르고 다섯 레벨·회전 파일·다음 로그 없이 단독 이벤트 전달·30초 자동 생성·실제 JSON 배열·중앙 원문 저장·수집기 재시작·짧은 503 장애 후 재시도를 확인한다. 이 검증은 장기 장애 부하 시험이나 fsync 보장이 아니다.

## 기술 근거

[Vector file source](https://vector.dev/docs/reference/configuration/sources/file/)의 파일 추적·체크포인트와 [HTTP sink](https://vector.dev/docs/reference/configuration/sinks/http/)의 배치·버퍼·재시도 정의를 사용한다. 구현 버전은 [Vector v0.60.0](https://github.com/vectordotdev/vector/releases/tag/v0.60.0)으로 고정한다. Kafka는 수집기 뒤의 분산 버퍼가 필요해질 때 검토하며 지금의 두 WAS 데모에는 별도 운영 부담을 추가하지 않는다.

## 검증 지연과 제한된 진단

단독 이벤트 검증은 자동 생성을 잠시 멈춰 다른 로그가 정체를 풀지 못하게 한다. 공유 실행 환경의 지연과 영구 정체를 구분하기 위해 최대 30초 동안 관찰하고 실제 소요 밀리초를 남긴다. 이는 제품 응답 시간 목표나 Vector 배치 대기 2초의 변경이 아니다. 실패하면 파일의 행 수·JSON 오류 수·대기 이벤트 존재 여부와 HTTP 상태·배치 집계만 출력한다. 원문·eventId·주소·호스트명·진단 헤더는 출력하지 않는다. 파일 미기록, 수집기 전송 정체, 중앙 항목 거절을 구분하기 위한 검증용 진단이며 운영 지표를 추가하지 않는다.

## 2026-10-28 이커머스 시나리오와 30초 주기

기존 60초 주기를 사용자 요청에 따라 30초로 변경했다. 첫 자동 생성도 시작 30초 후다. 주기는 `sample.interval-ms` 한 설정으로 스케줄러와 `/api/status`에 함께 반영하며 화면은 응답의 `intervalSeconds`를 표시한다. 매 주기 다섯 레벨 중 하나를 균등 선택한 뒤 해당 레벨의 시나리오를 균등 선택한다. 실제 쇼핑몰 장애 빈도나 동일 주문의 순차 처리 과정을 재현하는 확률 모델이 아니다.

주문·결제·실재고 문제와 배송 문제를 아래처럼 세분화한다. 상품 옵션은 합성 의류 SKU이며 메시지에 합성 표시·오류 코드·원인·수치·관측한 처리 결과 또는 필요한 조치를 포함한다. 고객 이름·전화·주소·결제 수단·토큰 등 실제 정보는 사용하지 않는다.

| 영역 | 시나리오 코드와 상황 |
| --- | --- |
| 주문 | ORDER_TOTAL_CALCULATED 금액 계산(DEBUG), ORDER_ACCEPTED 접수(INFO), ORDER_DUPLICATE_BLOCKED 중복 차단(WARN), ORDER_PRICE_CHANGED 가격 변경 거절(WARN), ORDER_SAVE_FAILED 저장 실패(ERROR), ORDER_PAYMENT_STATE_MISMATCH 승인 후 주문 상태 불일치(ERROR) |
| 결제 | PAYMENT_ROUTE_SELECTED 승인 경로(TRACE), PAYMENT_APPROVED 승인(INFO), PAYMENT_DECLINED 승인 거절(WARN), PAYMENT_TIMEOUT 응답 시간 초과(ERROR), PAYMENT_AMOUNT_MISMATCH 승인 금액 불일치(ERROR), PAYMENT_REFUND_FAILED 환불 실패(ERROR) |
| 재고 | INVENTORY_CHECK_STARTED 조회 시작(TRACE), INVENTORY_RESERVATION_CHECKED 예약 가능량(DEBUG), INVENTORY_RESERVED 예약(INFO), INVENTORY_INSUFFICIENT 부족(WARN), INVENTORY_STOCK_MISMATCH 전산·실사 불일치(ERROR), INVENTORY_VERSION_CONFLICT 동시 차감 충돌 후 재시도 소진(ERROR) |
| 배송 | FULFILLMENT_DELAYED 출고 지연(WARN), FULFILLMENT_REQUEST_FAILED 창고 요청 실패(ERROR) |

예상 가능한 재고 부족·승인 거절·중복 요청은 WARN, 기술적 처리 실패·불일치는 ERROR로 구분했다. 결제 시간 초과는 결제 실패를 단정하지 않고 승인 여부 미확정과 승인 조회 필요를 남긴다. 재고는 `판매가능수량=실재고-예약수량` 예시와 별도로 전산 재고·창고 실사 차이를 구분한다. 메시지에 등장하는 롤백·판매 보류·환불 대기는 시뮬레이션 상황 설명이며 실제 비즈니스 작업을 실행한 결과가 아니다.

`GET /api/scenarios`는 code/category/label/level 목록을 제공한다. 기존 `POST /api/events`의 level/exception은 유지하고 선택 `scenario` 코드만 추가한다. 생략하면 레벨 안에서 랜덤, 지정하면 해당 시나리오를 생성하며 잘못된 코드·레벨 불일치·RANDOM과 코드 동시 지정은400이다. 수동 레벨·랜덤 생성과 예외 로그 버튼을 유지한다. 시나리오 선택은 레벨을 맞추고 레벨 변경은 시나리오 선택을 초기화한다. 예외 버튼은 ERROR 시나리오를 선택했으면 그 상황, 그 외에는 랜덤 ERROR 예외를 생성한다. 일반 수동 로그는 기존 exception=false 의미를 유지하며 자동 RANDOM에서 ERROR가 선택되면 도메인별 합성 예외를 포함한다.

예외는 샘플 내부에서 생성한 진단 객체이며 실제 PG·DB·창고 장애를 유발하지 않는다. 결제 시간 초과는 PaymentProcessingException과 SocketTimeoutException 원인 체인을 함께 남긴다. 중앙 로그 JSON 형식·검색 API·SSE·MySQL·Vector 설정은 바꾸지 않는다. 전체 파이프라인 검증은 두 WAS에서 모든20개 시나리오가 중앙 원문·레벨·예외 여부를 보존하는지와 잘못된 수동 요청의400을 확인한다.
