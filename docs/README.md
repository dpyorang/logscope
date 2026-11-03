# logscope 문서 읽기 안내

2026-11-03. 현재 원격 main의 구현을 이해하기 위한 기능별 출발점이다. 문서 이름에 draft가 남아 있거나 과거 기록에 미구현이라고 적혀 있어도, 최신 후속 기록과 구현 문서를 함께 확인한다. 이 안내는 기능 구현·배포 승인이 아니다.

## 처음 읽는 순서

1. [지금 구현된 것](implementation-status.md): 전체 흐름과 완료/후속 범위를 파악한다.
2. [로그 수집](features/collection/README.md): WAS의 로그가 중앙으로 오는 과정을 읽는다.
3. [저장과 중복](features/storage/README.md): MySQL과 트랜잭션을 읽는다.
4. [실시간 조회](features/realtime/README.md): SSE·cursor·재연결을 읽는다.
5. [검색](features/search/README.md): Elasticsearch·Nori·outbox를 읽는다.
6. [화면](features/frontend/README.md): SSR·상세·검색/실시간 모드를 읽는다.
7. [실행·검증](features/runtime/README.md): 로컬 실행과 운영 준비를 구분한다.
8. [다음 진행 제안](next-steps.md): 중간 점검과 배포 준비 순서를 읽는다.

## 문서를 찾는 두 가지 방법

기능을 이해하려면 features의 해당 폴더에서 시작한다. API 필드가 궁금하면 contracts, 선택 이유는 decisions, 시간순 개발 과정은 learning의 원본 문서로 이동한다. 같은 기술 설명을 여러 폴더에 복사하지 않고 링크로 연결한다.

| 위치 | 읽을 목적 |
| --- | --- |
| features/ | 기능별 질문·기술 역할·읽기 순서 |
| contracts/ | API 요청·응답·오류 등 정확한 명세 |
| decisions/ | 대안과 기술 선택 이유. 이전 제안 문서와 실제 채택 기록 구분 |
| learning/ | 영역별 시간순 학습·실패 원인·구현 경험 |
| design/ | 화면 구상·규격·이전 시안 |
| 기존 docs 루트 문서 | 구현 안내·운영·공통 정책의 원본 |

기존 파일은 이동·삭제하지 않았다. 기능별 폴더는 원본으로 가는 안내서이므로 오래된 링크를 깨지 않고 읽기 경로를 제공한다. 학습 기록은 기존 영역별 누적 형식을 유지한다. 기능별 본문 이동은 향후 필요할 때 기존 경로에 안내를 남기는 방식으로 별도 검토할 수 있다.

## 이전 설계와 현재 구현

과거 MVP 계획은 당시 목표와 판단 근거다. 최신 구현은 [현재 상태](implementation-status.md), 개별 구현 문서와 코드로 확인한다. 검증 방식 설명은 이번 문서 작업에서 전체 검증을 다시 수행했다는 뜻이 아니다. 성능 목표와 측정 결과를 구분한다.
