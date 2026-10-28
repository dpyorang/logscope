# 프론트 개발 환경 명세와 분야 간 합의 대기 사항

2026-10-27. 사용자가 **TypeScript + Next.js SSR과 프론트 개발 환경 추천안의 명세 반영을 승인**했다. 아래 프론트 기준은 채택하되 총괄·백엔드·인프라·디자인의 담당 범위에 영향을 주는 부분은 ‘미결정’으로 구분한다. 명세 채택은 설치·구현·호환성 검증 승인이 아니다. [프론트 연동 명세](frontend-integration-draft.md), [백엔드 API 명세](log-api-draft.md), [문서 정책](../documentation-policy.md)을 함께 참고한다. 기존 경로는 참조 보존을 위해 유지한다.

## 초기 환경 구현 후속 기준

2026-10-27: [개발·검증 문서](../frontend-development.md)에 실제 초기 환경 기준을 기록했다. TypeScript 7.0.2는 typescript-eslint 컴파일러 API 미지원으로 6.0.3으로 조정한다. Next·React·Node·npm은 기존 기준을 유지한다. Java Gradle·프론트 npm 역할과 verify.sh는 최신 AGENTS.md의 확정 기준이며 아래 이전 합의 대기 표시는 대체한다. 기존 제안과 이유는 보존한다.

이번 범위는 한글 기본 화면·동적 SSR·자체 live·검사·standalone 이미지·로컬 Compose·CI다. 백엔드 API·중계·기한·응답 한도·로그 목록·상세는 후속 작업이다.

## 최초 채택 버전과 도구 기록

공식 릴리스 확인일은 2026-10-27이다. 아래 조합은 설치·빌드로 검증한 조합이 아니다. 구현 시작 시 보안 공지·지원 상태·peer dependency를 다시 확인하고, 실제 호환성 검사 후 정확한 버전을 고정한다. 현재의 latest를 자동 추종하지 않는다.

| 항목 | 채택한 기준 | 이유와 상태 |
| --- | --- | --- |
| Next.js | 16.4.3, App Router | 공식 안정 릴리스 확인. 서버 렌더링과 Client Component를 함께 구성할 수 있다. 프론트 기준으로 채택 |
| TypeScript | 7.0.2, strict 검사 | 공식 안정 릴리스 확인. API 자료형과 화면 상태의 실수를 빌드 전에 발견한다. Next.js 타입 검사·플러그인과의 실제 호환성은 미검증이며 문제 시 근거를 기록해 대체 버전을 조율 |
| React·react-dom | 둘 다 19.3.1 | 공식 릴리스 확인, 두 패키지를 같은 버전으로 맞춘다. Next.js 16.4.3의 선언된 React 19 범위에 해당하지만 실제 SSR·hydration 검증은 별도 |
| Node.js | 24.22.1 LTS | 공식 보안 릴리스 확인. 장기 지원 계열로 개발·CI·실행 버전을 맞춘다. Java 서버와 별도의 Next.js 실행 환경이 필요 |
| 패키지 관리자 | npm 11.19.1 | Node.js 24.22.1 공식 소스에 포함된 버전. 별도 패키지 관리 도구를 추가하지 않아 초기 학습·설치가 단순하다. 최신 npm이라는 뜻은 아님 |
| 설치 재현 | package-lock.json, CI에서는 npm ci | 잠금 파일과 선언이 다르면 실패하게 하고 CI가 임의로 버전을 고치지 않도록 한다 |

직접 의존성은 정확한 버전으로 기록하고 잠금 파일을 함께 관리한다. 위 버전은 프론트의 초기 고정 기준이며 실제 호환성 검증은 아직 없다. 검증 실패나 후속 보안 업데이트가 있으면 근거와 변경 이유를 기록해 버전을 조정한다. packageManager 선언만으로 도구 버전이 강제되지는 않으므로 실제 버전 검사 방법과 설치·CI 연결은 인프라 합의 대기다. npm 선택은 프론트 기준으로 채택하지만 Gradle 공통 규칙에서의 역할 경계는 총괄 합의 전까지 미결정이다. React 타입 패키지·ESLint·포매터의 정확한 버전은 미결정으로 남긴다. 이번에 package.json·잠금 파일·설정 파일을 만들지 않는다.

## 채택한 프론트 폴더·빌드 기준

프론트 소스는 저장소의 `frontend/`에 모으고 Java 앱과 빌드 산출물을 분리한다. 작은 MVP이므로 별도 모노레포 관리 도구는 추가하지 않는다. 이는 향후 구현할 디렉터리 기준이며 지금 폴더나 앱을 생성하지 않는다.

| 용도 | 명령 또는 구성 기준 | 이유 |
| --- | --- | --- |
| 개발 | frontend에서 npm run dev → next dev | 화면 변경을 빠르게 확인 |
| 타입 검사 | npm run typecheck → tsc --noEmit | API 타입·화면 상태 오류 확인 |
| 정적 검사 | npm run lint → eslint, 별도 format:check | next build에 모든 품질 검사가 포함된다고 가정하지 않음 |
| 빌드 | npm run build → next build | SSR 서버용 빌드. ignoreBuildErrors 같은 우회 설정은 사용하지 않음 |
| 실행 산출물 | output: standalone, Node로 server.js 실행을 프론트 출력 기준으로 채택 | 필요한 실행 파일을 모으고 public과 .next/static도 함께 포함. 컨테이너·실행 구성은 인프라 합의 대기 |
| 통합 검증(미결정) | 기존 scripts/verify.sh에 프론트 설치·검사·빌드·필요한 UI 검증·이미지 빌드를 필수 단계로 연결하는 제안 유지 | 기존 검증을 보존하며 프론트 실패도 커밋을 막아야 함. 연결 방식과 UI 검증 도구·범위는 총괄·인프라 합의 대기 |

**총괄·인프라 합의 필요:** AGENTS.md의 Gradle Wrapper·Kotlin DSL 기준과 npm의 역할 경계를 명시해야 한다. 추천은 서버와 공통 검증의 기준을 기존대로 두고 프론트 의존성·Next 빌드에 npm을 사용하는 것이다. ‘Gradle만 사용’ 규칙에 npm 예외가 이미 허용됐다고 해석하지 않는다. Gradle에서 npm 작업을 호출할지 scripts/verify.sh에서 직접 순서대로 실행할지도 합의한다. 현재 verify.sh는 서버 clean build·Compose 검증·서버 이미지 빌드를 실행하므로 프론트 검증이 이미 연결돼 있다고 보고하지 않는다.

SSR은 런타임 서버가 필요하다. Java jar 안에 정적 파일만 넣는 방식이나 정적 export는 요청 시 SSR 요구와 맞지 않는다. Next.js 이미지와 Spring Boot 이미지를 별도로 만들고 Compose에 Next.js 서비스를 추가하는 안을 추천하며, 이미지·실행 구성 변경은 인프라 합의와 별도 구현 요청 후 진행한다. 새 GitHub Actions 도구를 추가한다면 기존 전체 SHA 고정·명시적 OS 규칙을 적용한다.

## 채택한 SSR과 화면 상태 기준

첫 요청은 Next.js 서버가 절대 from/to를 한 번 계산해 Spring Boot에서 첫 목록을 읽고 HTML로 렌더링한다. 같은 시각 경계·데이터·마지막 조회 시각을 Client Component에 전달해 브라우저가 즉시 별도 첫 조회를 반복하지 않게 한다. 필터 편집·페이지·상세·수동 갱신은 Client Component에서 관리한다. 모든 클릭마다 전체 HTML을 다시 받는 방식일 필요는 없다.

동적 요청 렌더링을 명시하고 Next.js의 로그 조회 fetch는 cache: no-store, Next.js 조회 응답은 Cache-Control: no-store로 한다. 빌드 시점·공유 캐시에 로그 목록을 저장하거나 ISR로 재사용하지 않는다. Server Component만 사용하면 요청 시 SSR이 자동 보장되는 것은 아니므로 동적 렌더링과 데이터 캐시 정책을 함께 확인한다. 상대 범위 최근 15분은 페이지 탐색 동안 절대 경계로 고정하고 새로고침에서만 재계산한다. 외부 프록시의 캐시 설정은 인프라 합의 대기다.

SSR 조회 실패도 빈 배열 성공으로 바꾸지 않고 기존 명세의 첫 조회 실패 화면과 수동 재시도를 제공한다. 응답·HTML·서버 로그에 API 내부 오류와 비밀값을 출력하지 않는다. 로그 문자열은 텍스트로 표시한다.

## 채택한 API 연결 방향과 미결정 세부사항

아래의 SSR 직접 조회·후속 조회 중계와 공통 서버 전용 API 클라이언트를 프론트 연결 방향으로 채택한다. 외부 노출 경로·오류·인증·네트워크 구성의 분야 간 합의까지 완료됐다는 의미는 아니다.

```text
첫 진입: 브라우저 → Next.js SSR → Spring Boot GET /api/v1/logs
후속 조회: 브라우저 → Next.js 조회 Route Handler → Spring Boot 목록·상세 API
수집: 샘플 WAS → Spring Boot POST /api/v1/logs
```

SSR에서는 자기 서버의 Route Handler를 HTTP로 다시 호출하지 않고 공통 서버 전용 API 클라이언트로 Spring Boot를 직접 조회한다. 후속 브라우저 요청은 같은 클라이언트를 사용하는 Route Handler가 중계한다. 이렇게 하면 첫 조회에 불필요한 경유가 없고 브라우저는 같은 출처만 호출하면 된다. Route Handler는 Spring Boot의 저장·검색·중복 판정 로직을 재구현하지 않는다.

**추천 중계 경로(미합의):** Next.js의 `GET /api/logs`, `GET /api/logs/{eventId}`를 Spring Boot의 기존 `/api/v1/logs` 및 상세에 연결한다. 수집 POST는 중계하지 않는다. 허용된 두 GET 경로만 제공하고 임의 URL을 받는 범용 프록시를 만들지 않는다. query·불투명 커서는 명세대로 인코딩·전달하고 내부를 해석하거나 조립하지 않는다.

내부 API 주소는 서버 전용 환경 변수로 읽고 NEXT_PUBLIC 접두사를 사용하지 않는다. 실제 변수명·주입 방식·네트워크 주소는 인프라 합의 대기이며 실제 주소·인증값은 문서나 Git에 기록하지 않는다. 브라우저가 백엔드를 직접 호출하지 않는 구조라면 해당 흐름에 CORS 설정이 필요하지 않지만, 중계 자체가 인증을 대신하지는 않는다. 인증 전 격리 개발 환경 원칙을 유지한다.

API 오류의 HTTP 상태·code·errors 및 유효한 Retry-After는 보존한다. 비JSON·네트워크·중계 기한 초과는 프론트 공통 오류로 구분하고 빈 성공으로 바꾸지 않는다. **백엔드·인프라 합의 필요:** 중계 실패의 정확한 코드·상태·응답 형식, 응답 크기 제한, 타임아웃 계층, 향후 인증 전달 범위를 정해야 한다. 제안 시작값은 SSR·중계의 백엔드 조회 총 기한 5초, 브라우저 조회 8초이며 자동 재시도는 하지 않는다. 이는 측정된 SLA가 아니고, 요청 취소 전파와 실제 응답 크기를 확인한 뒤 조정해야 한다.

## 미결정: 분야별 추가 합의 목록

| 대상 | 합의할 내용 |
| --- | --- |
| 총괄 | Next SSR 방향 확정의 공통 아키텍처 반영, npm과 Gradle 역할 경계. 이 문서만으로 공통 규칙을 변경하지 않음 |
| 인프라 | Node·npm 고정·설치 검증, 별도 Next 컨테이너, 실행 포트·내부 주소 주입, health 확인, CI·verify.sh 연결, 요청 기한·프록시 캐시 및 배포 산출물 |
| 백엔드 | 조회 중계 경로·오류 보존과 중계 고유 오류, 응답 크기·조회 기한, 인증 도입 시 전달 범위. 기존 수집·조회 API 경로는 변경하지 않는 안 |
| 디자인 | SSR 최초 로딩·실패·재시도 표현, 목록 갱신 중 상세 선택, 좁은 화면과 글꼴·간격·색상 규격. 기존 상태 명세를 우선 유지 |

프론트 내부의 남은 세부 결정은 React 타입·ESLint·포매터의 버전, UI 검증 도구·구체적 검사 범위와 SSR 동적 렌더링 설정 방법이다. 총괄·인프라와는 검증 진입점·실행 구성·요청 기한을, 백엔드와는 중계 API 세부사항을 먼저 맞춘 뒤 구체화한다. 특히 5초·8초 기한과 `/api/logs` 중계 경로는 이번 승인으로 확정하지 않는다.

채택한 프론트 기준은 위 버전·도구 선택·폴더·검사 및 빌드 명령·standalone 출력·SSR 분담·Next.js 캐시 비사용·조회 중계 방향이다. 분야별 추가 합의 목록의 내용과 미결정으로 표시한 세부사항은 제안으로 유지한다. 다른 채팅에 자동 전달되지 않았으며 이번 작업에서 메시지를 보내지 않았다. 초기 선행조건 해소·별도 구현 요청 전 앱 생성, 설치, 코드·Compose·CI 변경이나 커밋을 시작하지 않는다.

## 공식 근거와 변경 기록

2026-10-27 후속 변경: 사용자가 개발 환경 추천안의 명세 반영을 승인하여 프론트 기준을 채택 상태로 바꿨다. 버전 고정·npm·폴더·빌드는 재현 가능한 시작점을 마련하기 위해, SSR/Client 분담·공통 API 클라이언트는 화면 상태와 API 처리를 일관되게 하기 위해, 캐시 비사용은 오래된 로그가 재사용되는 일을 막기 위해 채택했다. npm 선택과 공통 Gradle 규칙의 조정, 실제 CI·컨테이너·중계 경로·오류·기한·디자인 규격은 타 분야 영향이 있어 미결정으로 남겼다. 기존 추천의 이유와 최초 작성 기록은 보존한다. 설치·구현·커밋·실행 검증은 하지 않았다.

- [Next.js 16.4.3 공식 릴리스](https://github.com/vercel/next.js/releases/tag/v16.4.3), [지원 정책](https://nextjs.org/support-policy), [React peer 범위](https://github.com/vercel/next.js/blob/v16.4.3/packages/next/package.json)
- [TypeScript 7.0.2 공식 릴리스](https://github.com/microsoft/TypeScript/releases/tag/v7.0.2), [React 19.3.1 공식 릴리스](https://github.com/react/react/releases/tag/v19.3.1)
- [Node.js 24.22.1 LTS 보안 릴리스](https://nodejs.org/en/blog/release/v24.22.1), [동봉 npm 11.19.1](https://github.com/nodejs/node/blob/v24.22.1/deps/npm/package.json), [npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/)
- [Server/Client Components](https://nextjs.org/docs/app/getting-started/server-and-client-components), [동적 요청 connection](https://nextjs.org/docs/app/api-reference/functions/connection), [fetch 캐시](https://nextjs.org/docs/app/api-reference/functions/fetch), [standalone 출력](https://nextjs.org/docs/app/api-reference/config/next-config-js/output), [조회 중계 패턴](https://nextjs.org/docs/app/guides/backend-for-frontend)

2026-10-27: 사용자 요청으로 SSR 방향을 확정하고 개발 환경의 구체적 추천을 처음 기록했다. 기존 연동 문서의 환경 후보 설명을 대체하는 후속 초안이며 기존 파일과 근거는 보존한다. 목적은 구현 전에 버전 재현성과 서버·프론트 실행 경계를 분명히 하는 것이다. 공식 자료 확인과 문서 작성만 수행했으며 설치·호환성·SSR·빌드·통합 검증 결과는 없다.

## 2026-10-28 검색 보기 추가

실시간 보기와 분리된 명시적 검색·기간/서버/레벨 필터를 추가한다. 이전 초기 구현의 검색 미제공 설명은 당시 범위로 보존하며, 현재 동작·오류 보존·검색 전용 시간 제한과 후속 범위는 [로그 검색 보기](../frontend-search-view.md)를 따른다. 검색은 백엔드 ES API, 상세와 실시간은 기존 API를 사용한다. 입력마다 호출하지 않고 조회 또는 Enter로 적용해 요청과 화면 변경을 제어한다.
