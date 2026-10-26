# 초기 설정 및 검증 기록

2026-10-26 기준. 로컬 개발용 초기 설정은 완료했고, 원격 브랜치 보호와 운영 배포는 아래 조건으로 남아 있다.

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

## 남은 조건

- main 보호 API는 현재 private 저장소에서 HTTP 403/GitHub Pro 필요 응답. 정책 파일은 준비됐으나 원격 강제 적용은 안 됨
- 운영 서버/도메인/registry/DB/secret manager는 사용자 확인 결과 미정. 실제 운영 배포는 보류
- 운영 데이터 복구 성능과 RPO/RTO는 운영 환경 결정 후 검증. 현재 복원 결과는 도메인 데이터 없는 로컬 초기 DB 기준
- 향후 public 전환 전 전체 이력과 내부 주소·개인정보를 다시 검토
