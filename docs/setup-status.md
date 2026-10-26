# 초기 설정 진행 상태

2026-10-26 기준. 설정 완료로 판정하지 않는다.

작성됨: 공식 Initializr 기반 Java 25 / Boot 4.1.2 / Maven Wrapper,
Boot 관리 JPA·Flyway·MySQL 드라이버, MySQL 8.4.13 테스트/Compose,
Spotless와 커밋 훅, CI, 환경/비밀 설정, 배포·복구 절차.

검증 및 연결 결과:

- 임시 JDK 25 다운로드 및 Spotless 적용/검사 성공, main/test Java 컴파일 성공
- Docker Desktop 4.97.0 설치·실행 완료. Docker 엔진 29.9.2 및 Compose v5.6.1 연결 확인
- 전체 Maven verify 성공: 테스트 1건 성공 / 오류 0건 / 생략 0건, MySQL 8.4.13 및 Flyway 검증 통과
- 실제 .env 없이도 CI Compose 검증이 가능하도록 예제 환경 파일 사용을 명시. 전체 검증 스크립트와 Docker 이미지 빌드 성공
- Git 2.39.5 설치 확인. 로컬 setup/bootstrap 브랜치로 초기화하고 origin 및 커밋 검증 훅 연결 완료
- 첫 커밋 40e4109: 기본 실행 환경. 커밋 훅의 전체 검증 통과
- 로컬 Compose 기동 성공. readiness HTTP 200 및 `{"status":"UP"}` 확인
- 대상 GitHub: https://github.com/dpyorang/logscope (사용자 지정). 사용자 직접 private 전환 후 API의 private=true 확인 완료. GitHub 연동과 admin/push 권한 확인, 원격 브랜치는 아직 없음
- 두 번째 커밋 8d280aa: CI와 운영 규칙. 세 번째 커밋 4f077eb: 비밀정보 차단. 각 커밋의 전체 검증 통과
- 실제 .env의 비밀값 2개는 기존 전체 이력에 없음을 확인. Gitleaks 이력·staged 검사에서 발견 0건
- 실제 .env는 gitignore와 dockerignore로 제외, 예제 파일에는 예시만 유지. private 상태여도 공개 저장소 기준으로 관리
- GitHub CLI 로그인 완료. main에 최초 기준 커밋과 setup/bootstrap 브랜치 업로드 완료
- PR: https://github.com/dpyorang/logscope/pull/1
- 최초 PR CI 성공: https://github.com/dpyorang/logscope/actions/runs/40322093911 (소스 e3c3e24)
- main 보호 조회 API가 HTTP 403을 반환: 현재 플랜에서 private 저장소 보호는 GitHub Pro 필요. private 유지, 보호 정책 파일은 준비됐으나 실제 원격 강제 적용 없음
- 로컬 백업·격리 복원 리허설 성공: MySQL 8.4.13 덤프 → 별도 DB 복원 → Flyway V1 성공 확인 → 복원 DB 사용 앱 readiness HTTP 200/UP. 검증용 임시 컨테이너는 정리함
- 운영 배포 대상/registry/DB/secret manager가 미지정. 실제 배포·복구 리허설 없음

남은 작업: 최종 PR CI 확인과 리뷰/병합, GitHub Pro 등 private 브랜치 보호 지원 플랜 준비 후 정책 적용·조회, 운영 배포 대상 확정.

예정 커밋 단위(각 커밋 직전 전체 검증 필수):

1. build: bootstrap Java 25 application with MySQL and Docker
2. ci: enforce verification and document operating rules

각 커밋은 검증 훅의 전체 검사 성공 이후에만 생성한다.
