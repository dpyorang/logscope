import Link from "next/link";
import { connection } from "next/server";

export default async function Home() {
  await connection();

  return (
    <div className="shell">
      <header className="site-header">
        <Link className="brand" href="/" aria-label="logscope 홈">
          <span className="brand-mark" aria-hidden="true">
            L
          </span>
          logscope
        </Link>
        <span className="environment-label">개발 환경</span>
      </header>
      <main id="main-content" tabIndex={-1}>
        <p className="eyebrow">서버 로그 관측</p>
        <h1>
          흩어진 로그를
          <br />
          한곳에서 살펴보세요.
        </h1>
        <p className="intro">
          여러 서버의 로그에서 오류가 발생한 위치와 원인을 찾을 수 있도록
          준비하고 있습니다.
        </p>
        <section className="status-card" aria-labelledby="setup-heading">
          <span className="status-icon" aria-hidden="true">
            ✓
          </span>
          <div>
            <p className="status-label">초기 화면</p>
            <h2 id="setup-heading">로그 조회 연결을 준비하고 있습니다</h2>
            <p>
              현재 로그 데이터는 연결되지 않았습니다. 다음 단계에서 로그 목록과
              상세 내용을 확인할 수 있습니다.
            </p>
          </div>
        </section>
        <p className="footnote">
          이 화면은 프론트 개발 환경의 기본 화면입니다.
        </p>
      </main>
      <footer>
        logscope<span>로그를 읽는 더 나은 시작</span>
      </footer>
    </div>
  );
}
