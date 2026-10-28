# 로그 실시간 조회 명세

2026-10-28 사용자 요청으로 SSE 최초 조회·연결 사이 누락 방지, 재연결·중복 제거·일시정지/재개를 구현한다. 폴링 대신 서버가 새 로그를 전달하는 SSE를 선택했다. 브라우저와 서버의 양방향 통신은 필요하지 않다. 기존 정적 목록·상세·수집 API는 유지한다.

## API와 화면 책임

- `GET /api/v1/logs/live`: query 없음. `{items: Summary[], cursor: string, from: string, to: string, truncated: boolean}`. from/to는 서버가 계산한 최근 24시간 UTC 밀리초, 시작 포함·끝 제외다. 종료 경계는 DB의 첫 consistent read로 cursor를 읽은 뒤 현재 밀리초의 다음 경계로 정한다. DB 연결 대기 중 저장된 로그와 같은 밀리초 로그가 cursor에만 반영되고 목록에서 빠지는 것을 방지한다. 발생시각/eventId 내림차순 최신 50건이며 51건 이상이면 truncated=true다. 이 snapshot은 최신 50건 보기이며 전체 기록을 모두 받은 것으로 표현하지 않는다. 기존 목록의 501 동작은 변경하지 않는다.
- snapshot의 cursor와 items는 같은 MySQL repeatable-read view다. snapshot 이후 stream 연결 전에 저장된 로그도 cursor 이후 replay에 포함된다. 최초 SSR은 이 API를 직접 조회하고 응답을 클라이언트로 인계한다. 브라우저에서 같은 snapshot을 다시 요청하지 않는다.
- `GET /api/v1/logs/stream?after=<cursor>`: after 필수, 단일 값. Last-Event-ID가 있으면 after보다 우선한다. 두 값 모두 형식 검증한다. 그 밖의 query와 중복 인자는 400 VALIDATION_ERROR다. cursor는 서버가 발급한 불투명 문자열로 취급한다.
- 성공은 `text/event-stream`, `Cache-Control: no-store, no-transform`, `X-Accel-Buffering: no`다. `ready` 이벤트 data는 `{cursor}`이며 연결 시작점을 뜻한다. 클라이언트는 ready로 보관 cursor를 앞당기지 않는다. `log` 이벤트 id는 cursor, data는 `{cursor,item: Summary}`다. JSON은 UTF-8이며 원문을 HTML로 해석하지 않는다. 주석 heartbeat를 최대 약 15초 간격으로 전송하고 `retry:3000`을 안내한다. 서버 연결은 약 55~60초 뒤 종료해 자원을 재정리하고 같은 cursor부터 재연결한다.
- stream은 **저장된 모든 신규 로그**를 저장 순서로 전송한다. 브라우저는 발생시각 기준 최근24시간만 표시하고, 범위 밖 로그도 처리한 cursor는 진전한다. 발생시각이 늦거나 같은 로그도 저장 순서 cursor로 복구한다. 받은 items를 eventId로 중복 제거하고 발생시각/eventId 내림차순으로 정렬하며 최신50건만 메모리에 유지한다. 잘린 목록·새 snapshot은 전체 이력 복구가 아니다.
- 일시정지는 연결을 닫고 마지막 처리 cursor와 목록·상세를 유지한다. 재개는 그 cursor로 연결한다. 연결/재연결/일시정지/실패 상태를 구분하며 새 로그가 와도 선택 상세·초점·읽던 위치를 초기화하지 않는다. 새로고침은 사용자가 명시적으로 최신 snapshot을 다시 받는 동작이다.
- 브라우저는 같은 origin의 제한된 Next.js 중계만 사용한다. fetch 기반 SSE 파서는 정상 검증·처리한 cursor만 보관한다. EventSource가 프레임 해석 전에 Last-Event-ID를 진전시킬 수 있는 점과 HTTP 오류 분류를 고려한 선택이며, 반복 HTTP 조회 폴링을 의미하지 않는다. 내부 API 주소는 서버에만 둔다. 중계는 SSE 버퍼링을 하지 않고 취소를 상류로 전파하며 연결 시작·idle 기한과 프레임 크기 제한을 둔다. 일시정지 중 무제한 메모리 큐를 만들지 않는다.

## 저장·복구와 한도

V3 migration은 기존 log_events에 고유 stream_sequence와 지속 epoch/마지막 순서 행을 추가한다. 기존 로그도 migration에서 순서를 부여한다. INSERT는 해당 단일 상태 행을 FOR UPDATE로 잠근 뒤 로그와 순서를 같은 트랜잭션에서 저장한다. 잠금은 commit까지 유지하므로 작은 순서가 늦게 commit되어 cursor 뒤에 숨는 경쟁을 피한다. 중복·충돌·롤백은 순서를 진전하지 않고 이벤트를 재발행하지 않는다. AUTO_INCREMENT만으로 commit 순서를 가정하지 않는다.

DB의 저장 로그가 replay 원본이다. 메모리 신호는 commit 뒤 연결을 깨우는 힌트이고 로그 본문 큐가 아니다. 먼저 신호 버전을 읽고 DB를 조회한 뒤 변경이 없을 때만 대기해 조회/대기 사이 신호도 놓치지 않는다. 재시작 후에도 같은 DB의 cursor로 replay한다. 한 번에 최대50건만 읽고 전송하며 서버당 동시 연결은32개, 연결당 비동기 timeout은60초다. 연결당 최대128건 전달 뒤 정상 종료하며 같은 cursor로 재연결한다. 응답 초기화 전 SseEmitter 내부 버퍼도 이 건수로 한정한다. 슬롯은 HTTP 응답 완료와 송신 작업 종료가 모두 확인된 뒤 반환하므로 느린/막힌 송신이 생겨도 작업자 수가 상한을 넘지 않는다. 느린 연결의 재전송 자료를 메모리 무제한 큐에 쌓지 않는다.

잘못된 cursor는400 VALIDATION_ERROR, 다른 DB epoch는409 LIVE_CURSOR_INVALID, 현재보다 앞선 순서는409 LIVE_CURSOR_AHEAD, 연결 상한은503 LIVE_CAPACITY_EXCEEDED다. 연결 전 DB 장애는503 STORAGE_UNAVAILABLE, 연결 뒤 DB 장애는 `stream-error` data `{code:"STORAGE_UNAVAILABLE"}` 후 종료한다. cursor를 앞당기지 않고 마지막 정상 처리 위치에서 복구한다. 409에서는 누락 없는 복구로 가장하지 않고 새 snapshot이 필요함을 안내한다.

현재 로그 삭제/보존 정책은 구현하지 않는다. 임의 DB 행 삭제나 epoch 변경 뒤의 무손실 복구를 보장하지 않는다. 단일 DB·단일 앱의 개발 환경을 우선 검증하며 단일 순서 행은 쓰기를 직렬화하는 처리량 제약이 있다. 고부하/다중 앱 즉시 fan-out·인증·역방향 프록시 운영 설정은 후속 설계다. 다른 앱의 저장도 heartbeat 후 DB replay로 보일 수 있지만 다중 앱 지연 SLA는 없다. Vector의 배치 대기2초는 SSE로 제거되지 않는다.

## 선택 이유와 참고

브라우저가 주기적으로 묻는 폴링도 API 연동이지만, 사용자는 연결을 유지하며 새 로그를 받는 경험을 요청했다. SSE는 단방향 전송·이벤트 id·재연결 안내를 제공한다. 자동 재연결 자체는 DB replay나 exactly-once를 보장하지 않으므로 저장순서·DB 복구와 eventId 중복 제거를 별도로 구현한다.

- [MDN SSE](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Spring MVC 비동기 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [MySQL AUTO_INCREMENT 잠금](https://dev.mysql.com/doc/refman/8.4/en/innodb-auto-increment-handling.html)

## 프론트 후속 연결 권고와 현재 상태

최초 자동 연결, 재개 replay, 최신50 제한 안내를 제공한다. 최근24시간은 현재 시각 기준으로 이동시키고 새 로그가 없을 때 만료는 로컬 타이머로 처리한다. 수동 새로고침 성공 시 snapshot/cursor를 재설정하고 상세를 해제하며, 실패 시 기존 결과/cursor를 유지한다. 일시정지 중 새로고침 후에도 일시정지 상태를 유지한다. 중계 시작기한5초·idle35초·프레임64KiB, 재연결3초부터 지수 backoff 최대30초를 권고한다. 이는 프론트 구현에서 실제 검증할 사항이며 백엔드 구현 완료만으로 UI 완료를 뜻하지 않는다. 현재 프론트 채팅의 사용자 지시는 검토만 수행하는 상태이므로 화면 구현·커밋은 별도 착수 지시 후 진행한다.
