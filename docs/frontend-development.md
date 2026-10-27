# 프론트 초기 개발 환경과 검증

2026-10-27. TypeScript·Next.js SSR 기본 화면을 구성한다. 로그 API 연동은 다음 작업이다. [환경 명세](contracts/frontend-environment-draft.md)와 [공통 아키텍처](architecture.md)를 함께 참고한다.

## 실행과 검사

Node 24.22.1, npm 11.19.1을 사용한다. frontend/.node-version과 package.json에 고정하며 실제 값이 다르면 검증이 실패한다. 공식 도구를 설치하고 PATH에서 사용할 수 있어야 한다. 시스템 전체 설치는 자동으로 수행하지 않는다.

```sh
cd frontend
npm run check:runtime
npm ci
npm run dev
```

http://localhost:3000 에서 기본 화면을 확인한다. 개발 서버는 loopback으로 실행한다. 실행 산출물은 npm run build 후 HOSTNAME=127.0.0.1 npm run start로 확인한다. standalone에 public과 .next/static을 함께 복사한다.

개별 검사는 npm run typecheck, npm run lint, npm run format:check, npm run build, npm run test:e2e다. 포맷 수정은 npm run format 후 전체 검증을 다시 실행한다. 정확한 의존성은 package-lock.json으로 관리한다.

커밋 전 공통 진입점은 루트 ./scripts/verify.sh다. 비밀 검사 → Java clean build·실제 MySQL Testcontainers·Spotless → 프론트 runtime·npm ci·타입·lint·포맷·빌드·Chromium → Compose·서버 이미지 → 프론트 이미지·컨테이너 생존/SSR 검사를 실행한다. 필수 도구가 없으면 생략하지 않고 실패한다. verify-frontend.sh는 잠금 파일의 Playwright에 맞는 Chromium을 설치한다. Ubuntu CI는 playwright install --with-deps chromium으로 OS 라이브러리를 준비한다.

## 화면과 실행 경계

루트 페이지는 connection()으로 요청 시 서버에서 렌더링한다. 현재 로그가 연결되지 않았음을 명시하며 가짜 조회 결과를 보여주지 않는다. HTML 언어 ko, 본문 바로가기·랜드마크·초점·좁은 화면 줄바꿈을 제공한다. 로그 화면의 최종 디자인은 다음 작업이다.

GET /health/live는 자체 응답만 확인한다. UP은 API·DB 준비나 제품 정상 동작을 뜻하지 않는다. ready 정책은 백엔드 연결 단계에서 정한다.

Compose frontend는 127.0.0.1:3000의 별도 Node 서비스이며 현재 app/db 준비에 의존하지 않는다. 이미지는 기본 node 사용자로 server.js를 직접 실행한다. Compose에는 init과 15초 종료 유예를 둔다. 운영 Compose와 배포는 이번 범위가 아니다.

```sh
docker compose --env-file .env.example up --build frontend
```

.env.example은 예시다. 프론트에 실제 비밀값·내부 API 주소·NEXT_PUBLIC 설정은 추가하지 않는다.

## 버전 조정 이유

TypeScript 7.0.2의 tsc는 실행됐으나 typescript-eslint가 컴파일러 API 미지원 오류로 lint를 중단했다. [공식 전환 안내](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/)와 [지원 범위](https://typescript-eslint.io/users/dependency-versions/)를 참고해 TypeScript 6.0.3을 사용한다. 검사 우회 대신 단일 호환 컴파일러를 택했다.

ESLint 9.39.5도 후보로 실행했으나 npm에서 지원 종료 경고를 확인했다. 최종은 ESLint 10.13.0, @eslint/js 10.0.1, typescript-eslint 8.72.1, @next/eslint-plugin-next 16.4.3, eslint-plugin-react-hooks 7.1.1의 flat config다. eslint-config-next 내부 플러그인의 ESLint 9 peer 제한을 강제로 우회하지 않고 공식 Next·Hooks·TypeScript 권장 규칙을 직접 적용한다. Next 규칙에 따라 홈 링크도 Link를 사용한다.

Prettier 3.9.10, Playwright 1.64.1, React 타입 19.3.0, Node 타입 24.19.2를 고정한다. 버전은 재현 기준이며 영구 안전성을 보장하지 않는다. 갱신 때 새 검증과 변경 이유를 남긴다.

## 필수 UI 범위와 한계

Chromium 1 worker·재시도 0회로 실제 standalone 서버를 검사한다. JavaScript 없는 첫 HTML의 한글 제목·본문·no-store, 키보드 본문 바로가기·360px 넘침·브라우저 오류·API 미호출, health와 public 자산 제공을 확인한다. 이미지도 임의 호스트 포트의 일회 컨테이너에서 health·SSR을 확인한 뒤 제거한다. 기존 앱·DB·볼륨을 변경하지 않는다.

다른 브라우저·전체 접근성·API·검색·복구·부하 성능 검증은 아직 없다. 실패 보고서·trace·스크린샷은 Git에서 제외하고 CI 실패 자료는 합성 화면만 7일 보관한다.

## 변경 기록

- 2026-10-27: 사용자 요청으로 최신 main에서 분리한 환경에 기본 화면·검사·SSR 이미지와 검증 연결을 구현했다. 도구 호환 문제와 조정 이유는 위에 보존했다. 학습 기록은 필수 검증·작업 커밋 후 별도로 작성한다.

## 2026-10-27 최소 목록·상세 연결 후속 기록

[목록·상세 연결 기준](frontend-log-view.md)에 최근 15분 SSR·eventId 상세·서버 전용 설정·기본 실패 처리와 이번 단계의 중계/검증 기준을 기록했다. 초기 화면의 ‘API 미연결’ 설명은 당시 구현 기록으로 보존하며 현재 상태는 이 후속 문서를 따른다. 검색·필터·커서와 해당 복구 전체는 다음 작업이다.
