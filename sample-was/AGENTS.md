# 샘플 WAS Java 코드 규칙

루트 공통 규칙 및 [백엔드 코드 구조와 주석 규칙](../docs/backend-code-style.md)을 따른다.

- com.logscope.sample 기능 아래 controller/service/domain/dto/config/logging 등 실제 역할별 계층을 둔다. 파일이 하나여도 해당 계층에 배치한다.
- 컨트롤러에는 스케줄·자동 생성 상태·로그 생성 로직을 넣지 않는다. 스케줄 활성화는 config, 생성 흐름은 service, 합성 상황 정의는 domain, Logback 인코더는 logging에 둔다.
- 프로덕션 클래스와 직접 작성한 메서드 위에 짧은 한국어 Javadoc을 둔다. API에는 HTTP 메서드·경로·역할을 적는다.
- 상황 코드·레벨·eventId·JSON·자동 주기와 전송 흐름을 구조 변경에 숨겨 바꾸지 않는다. 단위 테스트와 두 WAS·Vector·MySQL 실제 수집 검증을 유지한다.
