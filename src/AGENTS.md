# 중앙 백엔드 Java 코드 규칙

이 디렉터리의 프로덕션 코드와 테스트는 루트 공통 규칙 및 [백엔드 코드 구조와 주석 규칙](../docs/backend-code-style.md)을 따른다.

- 기능 패키지 안에 controller/service/repository/client/domain/dto/validation/config/exception 등 실제 역할별 계층을 둔다. 파일이 하나여도 Config는 config, Controller는 controller에 둔다.
- 실행 진입점을 제외한 기능 코드를 기능 루트에 평탄하게 쌓지 않는다. 불필요한 빈 계층·추상화는 추가하지 않는다.
- 컨트롤러는 HTTP 경계, 서비스는 기능 흐름, 저장소는 SQL·트랜잭션, 클라이언트는 외부 연산을 담당한다. 계층 역참조를 추가하지 않는다.
- 프로덕션 클래스와 직접 작성한 메서드 위에 짧은 한국어 Javadoc을 작성한다. API는 HTTP 메서드·경로·역할, 내부 처리는 목적과 중요한 제약을 설명한다. 자동 record 접근자는 제외한다.
- 리팩토링에서도 API·JSON·오류·스키마·기존 트랜잭션과 복구 의미를 보존하고 단위·실제 통합 테스트 및 scripts/verify.sh를 유지한다.
