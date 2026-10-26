# 운영 및 복구 절차

## GitHub 연결과 보호

실제 저장소 소유자/이름을 확정하고 GitHub CLI 로그인 또는 연결 도구가 필요하다.
초기 검증이 통과한 커밋만 원격에 업로드한다. CI 최초 실행이 성공하면 main에
다음 보호 설정을 적용하고 GitHub API의 조회 응답으로 실제 적용을 확인한다.

- PR 필수, 승인 1명 이상, 새 커밋에 기존 승인 해제
- 필수 상태 검사: `verify` (CI 워크플로의 verify job), 최신 main 반영 필수
- 관리자도 보호 적용, 대화 해결 필수, force push 및 삭제 금지
- 기본 Actions 권한 read-only, PR의 secret 접근 금지

사용자 한 명만 관리하는 저장소라도 승인 요구를 임의로 완화하지 않는다.
GitHub 플랜/공개 여부에 따른 보호 지원 여부도 실제 적용 결과로 확인한다.
대상은 `dpyorang/logscope`다. 최초 CI 성공 후 `./scripts/configure-github.sh`로
저장된 정책을 적용하고 조회한다. 기존 저장소라면 기존 브랜치/보호 정책을 먼저 조회하여
의도하지 않은 정책 약화가 없는지 확인한다. 이 스크립트는 아직 실행되지 않았다.

## 환경과 비밀

local은 compose.yaml과 gitignore 처리된 .env를 사용한다. 운영은 별도 관리형
MySQL 8.4 LTS와 compose.prod.yaml을 사용한다. staging도 운영과 분리된 DB와
secret 파일을 사용하여 prod 프로필로 동일한 배포를 검증한다.

기본 프로필에는 DB 비밀 기본값이 없다. prod는 /run/secrets의 DB_USERNAME,
DB_PASSWORD 파일을 필수로 읽는다. secret manager에서 파일을 렌더링하고
호스트 접근 권한과 컨테이너 UID 10001의 읽기 권한을 제한한다.
Compose 파일 기반 secrets는 호스트 파일 암호화를 제공하지 않는다.
원격 DB URL은 `sslMode=VERIFY_IDENTITY`와 신뢰 가능한 CA를 설정한다.
운영 DB는 외부 공개하지 않고 앱과 백업 담당자의 네트워크만 허용한다.

DB 계정은 해당 스키마로 권한을 제한한다. 현재 앱 시작 시 Flyway를 실행하므로
마이그레이션 DDL 권한이 필요하다. 별도 migration job 도입 전 이 제약을 검토한다.
비밀 회전 시 새 자격증명을 발급하고 staging 검증 → 파일 교체 → 앱 재기동 →
readiness 확인 → 이전 자격증명 폐기 순서로 수행한다.

## 배포

1. 승인된 PR의 필수 CI 성공과 커밋 SHA를 확인한다.
2. CI에서 검증한 동일 SHA를 체크아웃하고 이미지를 빌드해 승인된 registry에 push한다.
   registry가 아직 지정되지 않았으므로 자동 push/운영 배포는 구성하지 않았다.
3. 이미지 digest, 이전 이미지 digest, Flyway 버전, 배포 담당자와 시간을 기록한다.
4. 아래 백업을 수행하고 별도 인스턴스 복원 검증을 마친다.
5. staging에 같은 digest와 migration을 적용해 readiness와 스모크 검사를 수행한다.
6. 운영 DB_URL, APP_IMAGE=registry/image@sha256:..., DB_USERNAME_FILE,
   DB_PASSWORD_FILE을 배포 환경에 설정한다. 비밀 값 자체를 명령행에 넣지 않는다.
7. `docker compose -f compose.prod.yaml pull` 후
   `docker compose -f compose.prod.yaml up -d --wait`를 실행한다.
   앱에는 Docker healthcheck가 없으므로 이 명령만으로 성공 판정하지 않는다.
8. 제한 시간 120초 내 `/actuator/health/readiness`가 HTTP 200인지 별도로 확인한다.
   실패 시 로그와 Flyway 상태를 확인하고 아래 복구 절차를 따른다.
9. 역방향 프록시에서 TLS와 접근 정책을 적용하고 오류율·지연·DB 연결을 확인한다.

health 외 관리 API를 외부에 노출하지 않는다. 현재 서비스 포트는 loopback에만 바인딩한다.
단일 Compose 배포에는 무중단/다중 노드 보장이 없다.

## 백업 및 복원

백업용 MySQL client option 파일을 secret manager에서 제공한다. 예: `/secure/backup.cnf`의
`[client]` 아래 host/user/password/ssl-mode를 설정한다. 파일은 소유자만 읽게 한다.

```sh
umask 077
mkdir -p backups
mysqldump --defaults-extra-file=/secure/backup.cnf \
  --single-transaction --routines --triggers --events \
  --no-tablespaces --set-gtid-purged=OFF logmonitoring > backups/logmonitoring.sql
shasum -a 256 backups/logmonitoring.sql > backups/logmonitoring.sql.sha256
```

덤프와 같은 시점에 DDL을 실행하지 않는다. 파일을 암호화하여 외부 백업 저장소로 전송하고
보존 정책과 복호화 접근을 관리한다. 체크섬만으로 복원 가능성을 판정하지 않는다.
별도 빈 MySQL 8.4.13 인스턴스에 복원한다(운영 대상에는 실행 금지).

```sh
shasum -a 256 -c backups/logmonitoring.sql.sha256
mysql --defaults-extra-file=/secure/restore.cnf logmonitoring < backups/logmonitoring.sql
```

restore.cnf가 격리된 대상인지 두 번째 담당자가 확인한다. 복원된 DB에서 해당 이미지로
앱을 시작해 Flyway 검증, 테이블/레코드 수, readiness를 확인한다.
운영 채택 전 RPO/RTO, 자동 백업 주기, 보존 기간, PITR/binlog 정책을 결정한다.

## 실패 시 복구

- 스키마가 이전 앱과 호환되면 APP_IMAGE를 기록된 이전 digest로 변경하고 앱만 재기동한다.
- migration은 expand/contract로 설계한다. 이미 적용한 SQL을 수정하거나 자동 역마이그레이션하지 않는다.
- 호환되지 않으면 트래픽과 쓰기를 중단하고 백업/PITR를 새 인스턴스에 복원한다.
  데이터 손실 범위와 복원 시점을 승인받은 뒤 DB 연결을 전환하고 검증 후 트래픽을 연다.
- Flyway 실패 시 원인과 실제 스키마 상태부터 조사한다. repair/clean을 자동 실행하지 않는다.
- `docker compose down -v`는 데이터를 삭제하므로 운영 복구 수단으로 사용하지 않는다.
