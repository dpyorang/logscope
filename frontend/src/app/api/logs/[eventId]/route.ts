import { failure, UUID } from "@/lib/logs";
import { loadDetail } from "@/lib/server/log-api";
import { reply } from "@/lib/server/response";
export const dynamic = "force-dynamic";
export async function GET(
  request: Request,
  context: { params: Promise<{ eventId: string }> },
) {
  const { eventId } = await context.params;
  if (!UUID.test(eventId) || new URL(request.url).search)
    return reply(failure(400, "VALIDATION_ERROR"));
  return reply(await loadDetail(eventId, request.signal));
}
