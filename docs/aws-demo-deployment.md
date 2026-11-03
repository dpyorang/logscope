# AWS 무료 플랜 단일 VM 데모 배포

2026-11-03 사용자 요청으로 AWS Free plan의 크레딧을 사용하는 포트폴리오·학습용 배포 구성을 추가한다. 실제 고객 데이터 운영 환경과 구분하며 합성 로그만 사용한다. 이전 [운영 기준](operations.md)과 로컬 Compose를 보존한다. 실제 배포 결과는 확인 후 별도로 기록한다.

## 범위와 선택 이유

EC2 한 대에서 Next.js·Spring·MySQL·Nori Elasticsearch·두 샘플 WAS·각 Vector와 Caddy를 실행한다. 별도 RDS·OpenSearch·NAT Gateway·로드 밸런서 없이 현재 기능을 작은 환경에서 시연한다. 시작 사양은 x86 2 vCPU·8GiB, Ubuntu 24.04, 암호화된 gp3 40GiB다. 이 값은 성능 보장이 아니며 컨테이너 메모리와 디스크를 확인해야 한다.

무료 플랜은 크레딧으로 비용을 지불하며 최대 6개월 또는 소진 시 종료된다. 크레딧을 받았다는 사실만으로 유료 계정을 무료 플랜으로 간주하지 않는다. 콘솔에서 실제 플랜·잔액·종료일을 확인하고 유료 전환·Organizations 참여·예약 구매는 하지 않는다. 24시간 켜 두면 크레딧이 6개월보다 훨씬 먼저 소진될 수 있다. 잔액이 20% 이하일 때 백업·시연 자료를 확보하고 종료 여부를 결정한다. 알림은 지출 차단 장치가 아니다.

공식 근거: [AWS 무료 플랜](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html), [EC2 무료 플랜 대상](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html).

## 네트워크와 접근

`compose.demo.yaml`은 독립 파일이다. 기존 Compose와 병합하지 않는다. 외부에는 Caddy의 80·443만 열고 SSH 22는 관리자의 현재 IP 하나로 제한한다. 80은 HTTPS 전환·인증서 발급에 사용한다. 모든 화면과 API를 HTTPS Basic 인증으로 보호하고 상위 서비스로 Authorization을 전달하지 않는다. 관리·health API와 수집 POST는 공용 프록시에 연결하지 않는다. 샘플 화면은 별도 was1·was2 호스트로 연결해 기존 절대 경로 API를 유지한다.

DB·검색·수집·애플리케이션 네트워크를 분리하고 내부 네트워크로 설정한다. DB·ES·백엔드·샘플 포트를 호스트에 게시하지 않는다. ES의 인증 비활성·단일 노드는 합성 데모 내부 네트워크에만 한정한다. 실제 고객 로그 운영에는 이 예외를 적용하지 않으며 기존 운영 문서의 인증·TLS 기준을 먼저 구현한다. 호스트 root 또는 앱 침해까지 막는 경계는 아니다.

별도 도메인을 구매하지 않는 경우 공개 IP를 해석하는 무료 DNS 서비스의 하위 도메인을 사용할 수 있다. 이는 외부 DNS 제공자와 공인 인증기관에 의존하며 인증서 투명성 로그에 호스트명이 공개된다. 자동 할당 IP는 서버 stop/start 후 달라질 수 있으므로 호스트 설정·DNS·인증서를 함께 확인한다. 실제 주소는 저장소에 넣지 않는다.

## 비밀과 이미지

DB 비밀번호와 웹 접근 자격은 무작위 생성하고 로그·명령행 인자·Git에 출력하지 않는다. Ubuntu systemd의 암호화 자격정보 저장소에 `systemd-creds encrypt --with-key=host`로 보관한다. `LoadCredentialEncrypted`가 시작 시 복호화하고 root 전용 `/run/logscope-demo`에 파일로 준비해 Compose secrets로 주입한다. DB·앱이 읽는 개별 파일은 컨테이너 UID가 읽을 수 있지만 상위 호스트 디렉터리는0700이다. 평문을 영구 환경 파일에 넣지 않는다.

이는 단일 호스트의 비밀 관리자이며 별도 IAM·감사 기능이 있는 AWS Secrets Manager와 다르다. host 키와 암호문을 모두 읽을 수 있는 root에 대한 보호는 제공하지 않는다. 재시작 시 같은 비밀을 사용하며, 이미 생성한 DB의 비밀번호 파일만 바꿔 회전했다고 보고하지 않는다. 이전 자격 폐기는 DB 계정·웹 접근을 각각 검증한 뒤 수행한다.

배포 환경 파일 `/etc/logscope-demo.env`에는 공개 호스트명과 검증 이미지 ID만 둔다. APP_IMAGE·FRONTEND_IMAGE·SAMPLE_IMAGE·SEARCH_IMAGE는 배포마다 같은 커밋에서 빌드한 로컬 이미지의 sha256 ID로 고정한다. 레지스트리 운영을 도입하기 전 데모는 서버에서 해당 Git SHA로 빌드하고 ID를 기록한다. 로컬 ARM·원격 x86 이미지는 바이너리가 동일하지 않으며 원격 실행 검사를 별도로 수행한다. 운영 registry digest 절차를 대체하지 않는다.

## 실행과 검증

필수 전체 검증은 기존 `scripts/verify.sh`를 유지하며 데모 Compose와 Caddy 문법 검사를 추가한다. 실제 비밀을 사용하지 않는 fixture를 임시로 만들고 정리한다. 서버 실행에는 다음 순서가 필요하다.

1. PR의 필수 verify 성공과 승인된 배포 SHA를 확인한다.
2. 그 SHA에서 서버용 이미지를 빌드하고 ID를 배포 환경 파일에 고정한다.
3. OS·Docker·Compose, ES의 `vm.max_map_count`, SSH와 IMDSv2·디스크 암호화를 확인한다.
4. 암호화 비밀, 호스트 설정과 systemd unit을 준비한다. 실제 값은 저장소 밖에 둔다.
5. `logscope-demo.service`를 설치·활성화해 재부팅에도 같은 이미지·비밀·볼륨을 사용한다.
6. readiness·SSR·미인증401·HTTPS·샘플 생성→중앙 상세·SSE·검색을 확인한다. `up --wait`만으로 완료 판정하지 않는다.
7. 앱 재시작 후 동일 eventId를 조회하고 DB 백업을 별도 임시 MySQL에 복원해 확인한다.

[Caddy Basic 인증](https://caddyserver.com/docs/caddyfile/directives/basic_auth), [SSE 중계](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy), [systemd 자격정보](https://systemd.io/CREDENTIALS/)를 따른다. 인증서 검증을 끄거나 웹 보안 경고를 우회하지 않는다.

## 보존·종료·복구

Docker 진단 로그는 컨테이너별10MB×3개로 제한한다. 샘플 파일은 기존 회전 정책을 유지한다. MySQL·ES 로그 데이터에는 아직 자동 보존 삭제가 없으므로 디스크80% 전에 자동 생성을 정지하고 다음 조치를 결정한다. 무단 데이터 삭제를 자동화하지 않는다. 40GiB가 영구 보관에 충분하다고 주장하지 않는다.

볼륨은 재기동·Compose stop 후 보존한다. `down --volumes`는 사용하지 않는다. 단일 VM과 디스크는 장애 지점이므로 백업은 암호화하여 별도 로컬 장비에 보관하고 복원 확인한다. 최초에는 합성 데이터만 있으며 RPO·RTO·HA를 보장하지 않는다. EC2 stop은 디스크 등 모든 비용의 종료가 아니다. 완전히 내릴 때는 백업 후 인스턴스·남은 EBS·스냅샷·공인 IP·기타 생성 자원을 대조하고 삭제 승인을 받아 정리한다. 무료 플랜 종료 후 데이터 접근을 기대하지 않는다.
