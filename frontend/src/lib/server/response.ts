import "server-only";
import type { Result } from "../logs";
export function reply<T>(result: Result<T>) {
  if (result.ok)
    return Response.json(result.data, {
      headers: { "Cache-Control": "no-store" },
    });
  const { status, code, errors, retryAfter } = result.problem;
  return Response.json(
    {
      type: "about:blank",
      title: code,
      status,
      code,
      detail: "The request could not be completed.",
      errors,
    },
    {
      status,
      headers: {
        "Content-Type": "application/problem+json",
        "Cache-Control": "no-store",
        ...(retryAfter ? { "Retry-After": retryAfter } : {}),
      },
    },
  );
}
