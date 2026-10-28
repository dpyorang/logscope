# logscope MVP 공통 아키텍처

2026-10-27 분야별 사용자 승인 기록을 취합했다. 제품의 책임과 프론트 채택 기준을 설명하며 기능 구현 승인이 아니다. 추가 실행·검증 정책은 [합의 검토안](decisions/mvp-cross-area-proposals.md)에서 제안 상태로 구분한다.

## 데이터 흐름과 책임

```text
샘플 WAS 1·2 → Logback JSON 파일 → WAS별 Vector → Spring Boot 배치 수집 API → MySQL
후속 확장 목표: WAS 3대

최초 화면: 브라우저 → Next.js SSR → Spring Boot 목록 조회 → MySQL
후속 조회: 브라우저 → Next.js 조회 중계 → Spring Boot 목록·상세 조회 → MySQL
```

- 샘플 앱 하나를 서로 다른 instanceId로 실행한다. 현재 두 대의 파일·Vector·배치 전송 기준은 [샘플 파이프라인](sample-log-pipeline.md)을 따른다. 이전 WAS 내부 전송 대기열 기준은 해당 기능에서 대체했다.
- Spring Boot가 수집·검증·중복 판정·저장·검색을 담당한다. API 경로는 /api/v1/logs와 /api/v1/logs/{eventId}다. DB 변경은 새 Flyway migration으로만 수행한다.
- Next.js는 화면 렌더링과 조회 중계를 담당한다. Spring Boot의 검색·저장 로직을 다시 구현하지 않는다. Vector의 배치 수집 POST는 Spring Boot로 직접 보낸다.
- SSR은 공통 서버 전용 API 클라이언트로 Spring Boot를 직접 조회한다. 자기 Route Handler를 HTTP로 다시 호출하지 않는다. 브라우저 후속 조회는 같은 클라이언트를 사용하는 제한된 Route Handler로 중계하는 방향을 채택했다. 브라우저용 정확한 경로는 합의 전이다.

## 프론트에서 채택한 기준

TypeScript·Next.js App Router SSR과 Client Component를 사용한다. 최초 SSR에서 최근 24시간의 절대 시간 경계를 한 번 계산하고 데이터·시간 범위·마지막 성공 시각을 브라우저에 인계한다. 브라우저 준비만으로 첫 조회를 반복하지 않는다. 필터·페이지·상세·수동 갱신은 브라우저 상태로 관리한다.

소스 위치는 frontend/, 패키지 관리 기준은 npm·정확한 직접 의존성·package-lock.json·CI npm ci다. 타입·lint·포맷 검사와 next build를 구분하고 standalone 출력에 server.js·public·.next/static을 포함하는 기준을 채택했다. 이는 아직 설치·구성·빌드로 검증하지 않았다.

[프론트 환경 명세](contracts/frontend-environment-draft.md)에 기록된 초기 버전 기준은 Next.js 16.4.3, TypeScript 7.0.2 strict, React·react-dom 19.3.1, Node.js 24.22.1 LTS, npm 11.19.1이다. 여기서는 승인 기록을 취합하며 해당 버전의 지원·보안·실제 조합 호환성을 재검증한 것으로 표시하지 않는다. 구현 요청 시 공식 근거와 설치·타입·SSR·hydration·빌드를 확인하고 문제 시 이유를 기록해 조정한다.

로그 조회는 동적 요청 렌더링, fetch의 cache: no-store, Next.js 조회 응답 Cache-Control: no-store를 기준으로 한다. ISR·공유 데이터 캐시는 사용하지 않는다. 외부 프록시 설정은 합의 전이다. 내부 API 주소는 서버 전용으로 두고 NEXT_PUBLIC에 노출하지 않는다.

## 확정과 추가 합의의 경계

Java 25·Spring Boot 4.1.2·MySQL 8.4.13 LTS와 Gradle Wrapper 기준은 유지한다. 후속 사용자 승인으로 프론트는 npm, Java는 Gradle을 사용하고 verify.sh를 공통 진입점으로 유지한다. Node 컨테이너·Compose·CI의 세부 연결은 프론트 기능 도입 단계에서 정한다. 기존 서버 검증을 완화하지 않고 프론트 검사를 추가한다.

Next.js·Spring Boot의 실제 이미지·포트·health·네트워크, 조회 중계 경로·오류·응답 크기·기한, 인증 전달 범위는 [추가 합의 검토안](decisions/mvp-cross-area-proposals.md)을 따른다. 인증 도입 전에는 로컬·격리 개발 환경에서 합성 로그로 검증한다.

## 변경 기록

- 2026-10-27: 기존에 없던 SSR 책임·실행 경계를 설명하기 위해 새 문서로 작성했다. 프론트에서 채택한 방향을 공통 배경으로 반영하고 분야 간 실행 정책과 실제 검증은 분리했다. 기존 명세 파일은 보존했다.

- 2026-10-27 후속 변경: [기능 단위 개발 방식](feature-development-workflow.md)에 따라 필요한 실행·중계 세부사항은 해당 기능에서 결정한다. 앞서 합의 대기였던 Gradle/npm 역할과 공통 검증 원칙은 사용자 승인으로 대체했다. 실제 설치·실행 설정은 변경하지 않았다.

## 프론트 초기 환경 후속 기록

2026-10-27: [개발·검증 문서](frontend-development.md) 기준으로 기본 환경을 추가했다. TypeScript는 실제 lint API 호환 오류로 7.0.2에서 6.0.3으로 조정하고 과거 기록은 보존한다. 기본 화면과 health/live는 백엔드에 연결하지 않는다. 위 조회 흐름은 후속 구현 목표이며 초기 환경이 전체 제품 검증을 뜻하지 않는다.

## 2026-10-27 최소 목록·상세 연결 후속 기록

[목록·상세 연결 기준](frontend-log-view.md)에 최근 24시간 SSR·eventId 상세·서버 전용 설정·기본 실패 처리와 이번 단계의 중계/검증 기준을 기록했다. 초기 화면의 ‘API 미연결’ 설명은 당시 구현 기록으로 보존하며 현재 상태는 이 후속 문서를 따른다. 검색·필터·커서와 해당 복구 전체는 다음 작업이다.

## 2026-10-27 파일 수집 후속 변경

사용자 승인으로 샘플 WAS 두 대의 자동·수동 로그를 Logback 파일에 남기고 Vector로 배치 수집한다. 파일 생성·전송·중앙 저장의 책임을 분리하고 Kafka 도입은 미뤘다. 구체적인 한도·부분 실패와 검증 범위는 [샘플 파이프라인](sample-log-pipeline.md)에 기록한다.

## 2026-10-28 SSE 실시간 조회

사용자가 서버 push를 요청해 [SSE 명세](contracts/log-sse.md)를 추가한다. 최초 `/live`의 목록·cursor는 동일 DB snapshot이고, `/stream`은 저장 확정 순서로 replay한 뒤 새 commit을 전달한다. 기존 정적 목록·상세·수집 API는 유지한다. DB 순서 상태의 행 잠금으로 commit 역전 누락을 막고, 메모리는 깨우기 신호만 보관한다. 서버 재시작에도 DB cursor와 로그로 복구한다. frontend의 SSR·중계·일시정지/재개는 별도 프론트 작업으로 연결하며 백엔드 구현만으로 화면 연동 완료라고 하지 않는다.

## 2026-10-28 Elasticsearch 검색 projection

사용자 요청으로 Redis 없이 MySQL 원본과 Elasticsearch 검색 색인을 분리한다. 원본·SSE 순서·색인 outbox를 같은 트랜잭션으로 저장하고 작업자가 검색 가능 반영 후 대기 항목을 제거한다. ES 장애에도 수집과 SSE를 유지하며 검색은503으로 구분한다. 기존 정적 목록과 SSE를 바꾸지 않는 `/api/v1/logs/search`의 한국어 분석·필터·최신 결과/대기 상태는 [검색 명세](contracts/log-search.md), 원리와 선택 이유는 [결정 기록](decisions/elasticsearch-log-search.md)을 따른다. 프론트 검색 UI는 별도 작업이다.

## 2026-10-28 검색 보기 추가

실시간 보기와 분리된 명시적 검색·기간/서버/레벨 필터를 추가한다. 이전 초기 구현의 검색 미제공 설명은 당시 범위로 보존하며, 현재 동작·오류 보존·검색 전용 시간 제한과 후속 범위는 [로그 검색 보기](frontend-search-view.md)를 따른다. 검색은 백엔드 ES API, 상세와 실시간은 기존 API를 사용한다. 입력마다 호출하지 않고 조회 또는 Enter로 적용해 요청과 화면 변경을 제어한다.
