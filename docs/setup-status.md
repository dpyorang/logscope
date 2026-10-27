# 초기 설정 및 검증 기록

2026-10-27 기준. 실제 환경 검증, GitHub 연결, main CI와 브랜치 보호 적용을
확인하여 도메인 개발 착수를 위한 초기 세팅 선행조건을 충족했다.
운영 배포 준비 완료를 뜻하지 않으며 운영 관련 미완료 사항은 아래에 구분한다.

## 현재 구성

- Java 25, Spring Boot 4.1.2, Gradle 9.8.2 Wrapper와 Kotlin DSL
- 프로젝트 좌표 com.logscope:logscope, Java 패키지 com.logscope
- JPA/Hibernate/JDBC/Flyway/Testcontainers는 Boot BOM 관리 버전 사용
- MySQL 8.4.13 LTS, Flyway V1 초기 기준, ddl-auto=validate
- Spotless 8.10.4와 Java 25 호환 google-java-format 1.30.0
- Gradle 배포본 공식 SHA-256 고정, build에 테스트와 포맷 검사 연결
- CI의 Gradle 캐시 및 build/test-results/test, build/reports/tests/test 경로 사용
- 실제 .env와 비밀/백업 파일은 Git 및 Docker 빌드에서 제외
- Gitleaks로 staged 변경과 전체 Git 이력 검사, 앱에는 DB 관리자 비밀번호를 전달하지 않음

## 확인한 결과

- Docker Desktop 4.97.0, 엔진 29.9.2, Compose v5.6.1 연결 확인
- 기존 임시 경로의 Temurin JDK 25.0.5 실행 확인. 기본 Java 런타임은 없으며 시스템 전체 영구 설치는 별도임
- 프로젝트명과 Codex 표시 이름은 logscope, 연결 폴더는 /Users/pyorang/Documents/ChatGPT/logscope
- GitHub 저장소 https://github.com/dpyorang/logscope 연결 및 CLI 인증 확인. 2026-10-27 사용자가 public으로 전환
- 초기 설정 PR #1 병합, main 549322d의 CI 성공 확인
- 로컬 백업을 격리된 MySQL에 복원하고 Flyway V1 및 복원 DB 사용 앱의 readiness UP 확인
- 이름 변경 시 기존 DB 백업과 이전 Docker 볼륨 보존
- Gradle 전환 후 clean build 성공: 실제 MySQL 테스트 1건, 실패/오류/생략 0건
- Gradle 기반 전체 verify 스크립트, 비밀 검사, Compose 검증, Docker 이미지 빌드 성공
- Gradle로 만든 이미지로 로컬 앱을 교체한 후 readiness HTTP 200/UP 확인
- 커밋은 검증 훅의 전체 검사 성공 이후에만 생성하며, 전환 PR/원격 CI 결과는 GitHub에 기록

## 2026-10-27 재확인 및 선행조건 충족 근거

- PR #4에서 worktree 비밀 검사와 1인 개발 보호 정책을 수정하고 병합
- 병합 main 커밋: `ed5a827397e8bf579cdebe14373ac1d9b8c5590b`
- [PR #4 CI](https://github.com/dpyorang/logscope/actions/runs/40453422225)와
  [병합 main CI](https://github.com/dpyorang/logscope/actions/runs/40453641514)의 `verify` 성공 확인
- 커밋 훅의 전체 검증 성공: staged·전체 Git 이력 비밀 검사, Gradle clean build,
  실제 MySQL Testcontainers 테스트 1건(실패·오류·생략 0건), Spotless,
  Compose 검증과 Docker 이미지 빌드
- 일반 clone과 worktree에서 전체 이력 검사 확인. 합성 비밀값으로 staged 및
  다른 브랜치에서 삭제된 비밀 탐지를 검증했고 shallow 저장소는 거부함
- main 보호 API 재조회: `protected=true`, PR 필수, 승인 0명,
  CI `verify` 필수, 최신 main 반영 필수, 관리자 적용, 대화 해결 필수,
  강제 푸시와 브랜치 삭제 금지. 정책 파일과 운영 문서에도 반영
- Git 원격 main 조회와 `core.hooksPath=.githooks` 확인
- 로컬 앱 `/actuator/health/readiness`의 HTTP 성공 및 `UP` 재확인
- 공개 전 전체 Git 이력, PR 3건과 Actions 9건의 로그를 점검하여 실제 비밀값을
  발견하지 못함. 당시 조회되는 댓글·리뷰·아티팩트는 없었으며 삭제·만료 자료는 검사 범위 밖임
- 과거 작성자 로컬 이메일·Mac 호스트명과 문서의 로컬 폴더 경로 노출은 사용자에게
  설명했고 사용자가 기존 이력을 유지한 채 공개하기로 결정함

위 결과는 해당 커밋과 확인 시점의 근거다. 이후 작업은 최신 AGENTS.md를 확인하고
API 명세·화면 규격·전송 정책의 분야 간 합의를 거쳐 진행한다.

## 운영 및 후속 관리 사항

- 개발 착수 선행조건의 미완료 항목은 없음. 아래 운영 과제는 별도 범위임
- 운영 서버/도메인/registry/DB/secret manager는 사용자 확인 결과 미정. 실제 운영 배포는 보류
- 운영 데이터 복구 성능과 RPO/RTO는 운영 환경 결정 후 검증. 현재 복원 결과는 도메인 데이터 없는 로컬 초기 DB 기준
- 공개 이후에도 매 커밋 비밀 검사와 변경 검토를 지속하고 실제 비밀값·개인정보를 새로 포함하지 않음
- 임시 JDK 경로에 의존하므로 실행 시 경로를 지정하며 영구 개발 도구 설치는 별도 관리

## 최초 설정 기록 보존

아래는 2026-10-26의 기록이며 private 저장소·브랜치 보호 미완료 등 당시 상태를 설명한다. 현재 상태는 위 2026-10-27 후속 기록을 따른다.

2026-10-26 기준. 로컬 개발용 초기 설정은 완료했고, 원격 브랜치 보호와 운영 배포는 아래 조건으로 남아 있다.

### 현재 구성

- Java 25, Spring Boot 4.1.2, Gradle 9.8.2 Wrapper와 Kotlin DSL
- 프로젝트 좌표 com.logscope:logscope, Java 패키지 com.logscope
- JPA/Hibernate/JDBC/Flyway/Testcontainers는 Boot BOM 관리 버전 사용
- MySQL 8.4.13 LTS, Flyway V1 초기 기준, ddl-auto=validate
- Spotless 8.10.4와 Java 25 호환 google-java-format 1.30.0
- Gradle 배포본 공식 SHA-256 고정, build에 테스트와 포맷 검사 연결
- CI의 Gradle 캐시 및 build/test-results/test, build/reports/tests/test 경로 사용
- 실제 .env와 비밀/백업 파일은 Git 및 Docker 빌드에서 제외
- Gitleaks로 staged 변경과 전체 Git 이력 검사, 앱에는 DB 관리자 비밀번호를 전달하지 않음

### 확인한 결과

- Docker Desktop 4.97.0, 엔진 29.9.2, Compose v5.6.1 연결 확인
- 임시 JDK 25를 사용해 검증. JDK의 시스템 전체 영구 설치는 별도임
- 프로젝트명과 Codex 표시 이름은 logscope, 연결 폴더는 /Users/pyorang/Documents/ChatGPT/logscope
- 비공개 GitHub 저장소 https://github.com/dpyorang/logscope 연결 및 CLI 인증 완료
- 초기 설정 PR #1 병합, main 549322d의 CI 성공 확인
- 로컬 백업을 격리된 MySQL에 복원하고 Flyway V1 및 복원 DB 사용 앱의 readiness UP 확인
- 이름 변경 시 기존 DB 백업과 이전 Docker 볼륨 보존
- Gradle 전환 후 clean build 성공: 실제 MySQL 테스트 1건, 실패/오류/생략 0건
- Gradle 기반 전체 verify 스크립트, 비밀 검사, Compose 검증, Docker 이미지 빌드 성공
- Gradle로 만든 이미지로 로컬 앱을 교체한 후 readiness HTTP 200/UP 확인
- 커밋은 검증 훅의 전체 검사 성공 이후에만 생성하며, 전환 PR/원격 CI 결과는 GitHub에 기록

### 남은 조건

- main 보호 API는 현재 private 저장소에서 HTTP 403/GitHub Pro 필요 응답. 정책 파일은 준비됐으나 원격 강제 적용은 안 됨
- 운영 서버/도메인/registry/DB/secret manager는 사용자 확인 결과 미정. 실제 운영 배포는 보류
- 운영 데이터 복구 성능과 RPO/RTO는 운영 환경 결정 후 검증. 현재 복원 결과는 도메인 데이터 없는 로컬 초기 DB 기준
- 향후 public 전환 전 전체 이력과 내부 주소·개인정보를 다시 검토

## 문서 취합 변경 기록

- 2026-10-27: origin/main ee949b0의 인프라 기록을 로컬 공통 문서에 반영하고 이전 기록을 보존했다. 이번 문서 취합에서 환경·원격 보호·전체 검증을 다시 실행한 것은 아니다.
