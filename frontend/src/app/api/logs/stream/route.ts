import { failure } from "@/lib/logs";
import { cursor } from "@/lib/live";
import { proxyStream } from "@/lib/server/log-stream";
import { reply } from "@/lib/server/response";
export const dynamic = "force-dynamic";
export const runtime = "nodejs";
export async function GET(request: Request) {
  if (request.url.length > 8192)
    return reply(failure(414, "REQUEST_URL_TOO_LONG"));
  const params = new URL(request.url).searchParams;
  const after = params.get("after");
  const last = request.headers.get("Last-Event-ID");
  if (
    [...params.keys()].some((key) => key !== "after") ||
    params.getAll("after").length !== 1 ||
    !cursor(after) ||
    (last !== null && !cursor(last))
  )
    return reply(failure(400, "VALIDATION_ERROR"));
  return proxyStream(last ?? after, request.signal);
}
