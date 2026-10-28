import { failure } from "@/lib/logs";
import { parseSearch } from "@/lib/search";
import { loadSearch } from "@/lib/server/log-api";
import { reply } from "@/lib/server/response";
export const dynamic = "force-dynamic";
export async function GET(request: Request) {
  if (request.url.length > 8192)
    return reply(failure(414, "REQUEST_URL_TOO_LONG"));
  const query = parseSearch(new URL(request.url).searchParams);
  if (!query.ok) return reply(query);
  return reply(await loadSearch(query.data, request.signal));
}
