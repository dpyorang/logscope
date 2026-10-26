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
- 대상 GitHub: https://github.com/dpyorang/logscope (사용자 지정). GitHub 연동과 admin/push 권한 확인 완료. 공개 저장소이며 아직 원격 브랜치가 없음
- 공개 저장소로의 최초 push는 자동 승인 검토에서 구체적 게시 승인 필요로 거절됨. 사용자 승인 전 업로드하지 않음
- 원격 CI 실행·main 브랜치 보호 적용은 최초 업로드 대기 중. GitHub 연결 도구에는 브랜치 보호 변경 기능이 없어 적용 시 CLI 관리자 인증 또는 웹 설정 접근 필요
- 운영 배포 대상/registry/DB/secret manager가 미지정. 실제 배포·복구 리허설 없음

예정 커밋 단위(각 커밋 직전 전체 검증 필수):

1. build: bootstrap Java 25 application with MySQL and Docker
2. ci: enforce verification and document operating rules

각 커밋은 검증 훅의 전체 검사 성공 이후에만 생성한다.
