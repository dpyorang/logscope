import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "logscope | 로그 관측",
  description: "여러 서버의 로그를 한곳에서 확인하는 logscope",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>
        <a className="skip-link" href="#main-content">
          본문으로 바로가기
        </a>
        {children}
      </body>
    </html>
  );
}
