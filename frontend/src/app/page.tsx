import { connection } from "next/server";
import { LogExplorer } from "@/components/log-explorer";
import { recentRange } from "@/lib/logs";
import { loadSnapshot } from "@/lib/server/log-api";
export default async function Home() {
  await connection();
  const range = recentRange();
  const result = await loadSnapshot();
  return (
    <LogExplorer
      initialRange={range}
      initialResult={result}
      initialSuccessAt={result.ok ? new Date().toISOString() : null}
    />
  );
}
