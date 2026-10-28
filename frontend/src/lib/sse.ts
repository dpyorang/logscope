export const FRAME_LIMIT = 65_536;
export const START_TIMEOUT = 5000;
export const IDLE_TIMEOUT = 35_000;
export type Frame = { event: string; id: string; data: string };

// UTF-8 문자와 CR/LF/CRLF가 네트워크 조각 사이에 나뉘어도 동일하게 해석한다.
// 미완성 줄/프레임까지 한도를 적용하고 EOF의 미완성 이벤트는 처리하지 않는다.
export class SseParser {
  private line: number[] = [];
  private bytes = 0;
  private cr = false;
  private endedAtCR = false;
  private firstLine = true;
  private data: string[] = [];
  private event = "";
  private id = "";
  constructor(
    private accept: (frame: Frame) => void,
    private limit = FRAME_LIMIT,
  ) {}
  push(chunk: Uint8Array) {
    for (const byte of chunk) {
      if (this.cr) {
        this.cr = false;
        if (byte === 10) {
          // CRLF는 하나의 줄끝이다. 끝난 프레임의 LF는 이미 여유를 확보했다.
          if (!this.endedAtCR) this.count();
          continue;
        }
      }
      this.count();
      if (byte === 13) {
        this.cr = true;
        this.endedAtCR = this.endLine(true);
      } else if (byte === 10) this.endLine();
      else this.line.push(byte);
    }
  }
  private count() {
    if (++this.bytes > this.limit) throw new Error("STREAM_FRAME_TOO_LARGE");
  }
  private endLine(reserveLF = false): boolean {
    let line = new TextDecoder("utf-8", {
      fatal: true,
      ignoreBOM: true,
    }).decode(Uint8Array.from(this.line));
    this.line = [];
    if (this.firstLine) {
      line = line.replace(/^\uFEFF/, "");
      this.firstLine = false;
    }
    if (!line) {
      // CR에서 즉시 처리하되 뒤따를 수 있는 LF까지 한도 안에 둔다.
      if (reserveLF && this.bytes + 1 > this.limit)
        throw new Error("STREAM_FRAME_TOO_LARGE");
      const frame = {
        event: this.event || "message",
        id: this.id,
        data: this.data.join("\n"),
      };
      const hasData = this.data.length > 0;
      this.bytes = 0;
      this.event = "";
      this.id = "";
      this.data = [];
      if (hasData) this.accept(frame);
      return true;
    }
    if (line.startsWith(":")) return false;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") this.data.push(value);
    else if (field === "event") this.event = value;
    else if (field === "id") {
      if (value.includes("\0")) throw new Error("STREAM_INVALID_RESPONSE");
      this.id = value;
    }
    return false;
  }
  finish() {
    if (this.bytes !== 0) throw new Error("STREAM_INCOMPLETE_FRAME");
  }
}
export async function consume(
  response: Response,
  controller: AbortController,
  accept: (frame: Frame) => void,
) {
  if (!response.body) throw new Error("STREAM_INVALID_RESPONSE");
  const reader = response.body.getReader();
  const parser = new SseParser(accept);
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    while (!controller.signal.aborted) {
      timer = setTimeout(() => controller.abort(), IDLE_TIMEOUT);
      const chunk = await reader.read();
      clearTimeout(timer);
      if (controller.signal.aborted) throw new Error("STREAM_CANCELLED");
      if (chunk.done) {
        parser.finish();
        return;
      }
      parser.push(chunk.value);
    }
  } finally {
    clearTimeout(timer);
    controller.abort();
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
