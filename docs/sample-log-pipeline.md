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

WAS마다 시작 60초 후부터 60초 간격으로 TRACE·DEBUG·INFO·WARN·ERROR 중 한 레벨의 합성 로그를 만든다. 수동 생성은 레벨 지정·랜덤·ERROR 예외 생성을 제공하며 자동 생성은 일시 정지할 수 있다. 정지 상태는 메모리에만 두며 재시작하면 켜진다. 브라우저의 최근 항목은 해당 페이지에서 누른 수동 요청 20건이다. 중앙 수집 완료 목록은 logscope에서 확인한다.

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
| 개별 행 | 최대 256KiB. 이번 생성기는 짧은 합성 메시지와 고정 예외만 만들며 범용 파일 수집기는 아니다 |

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

파이프라인 테스트는 임의 이름의 네트워크·DB·로그/체크포인트 볼륨만 만들고 종료 시 자기 자원만 제거한다. 실제 브라우저에서 양쪽 수동 예외 버튼을 누르고 다섯 레벨·회전 파일·다음 로그 없이 단독 이벤트 전달·60초 자동 생성·실제 JSON 배열·중앙 원문 저장·수집기 재시작·짧은 503 장애 후 재시도를 확인한다. 이 검증은 장기 장애 부하 시험이나 fsync 보장이 아니다.

## 기술 근거

[Vector file source](https://vector.dev/docs/reference/configuration/sources/file/)의 파일 추적·체크포인트와 [HTTP sink](https://vector.dev/docs/reference/configuration/sinks/http/)의 배치·버퍼·재시도 정의를 사용한다. 구현 버전은 [Vector v0.60.0](https://github.com/vectordotdev/vector/releases/tag/v0.60.0)으로 고정한다. Kafka는 수집기 뒤의 분산 버퍼가 필요해질 때 검토하며 지금의 두 WAS 데모에는 별도 운영 부담을 추가하지 않는다.
