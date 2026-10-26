# logmonitoring

Java 25 · Spring Boot 4.1.2 · Boot 관리 JPA/Flyway · MySQL 8.4.13 LTS.
초기 인프라 설정 단계이며 도메인 기능은 아직 없다.

## 로컬 실행

JDK 25, Git, Docker Engine/Desktop와 Compose v2가 필요하다.

```sh
cp .env.example .env
# .env의 두 비밀번호를 서로 다른 임의 값으로 교체한다.
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health/readiness
```

호스트에서 Java로 실행하려면 `docker compose up -d db` 후 DB_PASSWORD를
환경 변수로 주입하고 `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`을 실행한다.
Spring은 .env를 직접 읽지 않는다. .env의 DB_URL은 Compose 내부용이다.

## 검증 및 커밋

```sh
git config core.hooksPath .githooks
./mvnw spotless:apply
./scripts/verify.sh
```

검증에는 Docker가 필수다. Testcontainers는 실제 MySQL에 대해 컨텍스트 시작,
Flyway 적용·검증·재실행과 연결을 확인한다. 포맷 검사는 Maven validate 단계에 결합되어 있다.
커밋은 검증 성공 후 기능별로 나눈다. CI도 같은 스크립트를 실행한다.

운영 구성과 GitHub 설정은 [운영 절차](docs/operations.md), 공통 규칙은 [AGENTS.md](AGENTS.md)를 따른다.

## 초기 세팅 완료 조건

- 로컬 전체 검증 통과 및 기능별 커밋
- GitHub 원격 연결과 PR CI 성공
- main 보호 적용 후 API로 설정 확인
- Docker 기동 및 readiness 성공
- 운영 대상 지정 후 별도 DB에서 백업 복구 리허설

현재 통과 여부는 [설정 진행 상태](docs/setup-status.md)에 기록한다.
