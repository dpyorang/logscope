import { failure } from "@/lib/logs";
import { loadSnapshot } from "@/lib/server/log-api";
import { reply } from "@/lib/server/response";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  if (new URL(request.url).search)
    return reply(failure(400, "VALIDATION_ERROR"));
  return reply(await loadSnapshot(request.signal));
}
