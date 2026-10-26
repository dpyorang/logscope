# 공통 작업 규칙

- 프로젝트 이름은 logscope, Maven 좌표는 com.logscope:logscope, 기본 Java 패키지는 com.logscope다.

- 초기 세팅의 실제 검증·GitHub 연결·브랜치 보호 확인이 끝나기 전 도메인 기능을 구현하지 않는다.
- Java 25, Spring Boot 4.1.2, MySQL 8.4.13 LTS를 사용한다. JPA, Hibernate, JDBC, Flyway, Testcontainers는 Boot BOM 버전을 따른다.
- Maven Wrapper만 사용한다. 기능 단위 Conventional Commit을 작성한다.
- 매 커밋 직전 `./scripts/verify.sh`가 성공해야 한다. 빌드, 실제 MySQL Testcontainers 테스트, Spotless 검사, Compose 검증 및 이미지 빌드 실패 시 커밋하지 않는다.
- `git config core.hooksPath .githooks`를 설정한다. `--no-verify`, 테스트 스킵, Docker 미사용 시 테스트 자동 생략을 금지한다.
- 포맷 수정: `./mvnw spotless:apply`. 변경 후 전체 검증을 다시 실행한다.
- main 직접 푸시와 강제 푸시를 금지하고 PR과 CI `verify`를 통해 병합한다. 빈 원격 저장소에 최초 기준 커밋을 만드는 경우만 검증 통과 후 main을 생성할 수 있다.
- GitHub Actions는 지원 중인 공식 릴리스의 전체 커밋 SHA로 고정하고, 실행 OS는 명시적 버전으로 지정한다. 갱신 시 공식 릴리스와 CI 결과를 확인한다.
- 스키마 변경은 새 Flyway migration으로만 수행한다. 적용된 파일 수정, ddl-auto update/create, 운영 clean/repair를 금지한다.
- 기본 시간대는 UTC, open-in-view는 false, ddl-auto는 validate다.
- 비밀번호·토큰·실제 .env·백업을 커밋하거나 로그에 출력하지 않는다. 운영 비밀은 secret manager에서 파일로 주입한다.
- 저장소가 private여도 공개 저장소와 동일한 비밀 관리 규칙을 적용한다. 실제 비밀값은 Git에서 제외한 .env/외부 secret 파일에만 저장하고 .env.example에는 예시만 둔다.
- 매 커밋 전 scripts/check-secrets.sh로 금지 파일·staged 변경·전체 Git 이력을 검사한다. 검사 우회나 광범위 allowlist를 추가하지 않는다. 공개 전 전체 이력 검사와 운영 접속 정보 검토를 다시 수행한다.
- 배포와 복구는 docs/operations.md를 따른다. 운영 배포와 파괴적 DB 작업에는 명시적인 실행 승인이 필요하다.
- 검증을 실행하지 못했거나 원격 설정이 적용되지 않았으면 완료로 보고하지 않는다.
