import { failure, validRange } from "@/lib/logs";
import { loadList } from "@/lib/server/log-api";
import { reply } from "@/lib/server/response";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  if (request.url.length > 8192)
    return reply(failure(414, "REQUEST_URL_TOO_LONG"));
  const params = new URL(request.url).searchParams;
  if (
    [...params.keys()].some(
      (key) => !["from", "to"].includes(key) || params.getAll(key).length !== 1,
    )
  )
    return reply(failure(400, "VALIDATION_ERROR"));
  const range = { from: params.get("from") ?? "", to: params.get("to") ?? "" };
  if (!validRange(range)) return reply(failure(400, "VALIDATION_ERROR"));
  return reply(await loadList(range, request.signal));
}
