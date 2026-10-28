import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, writeFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";
import { chromium } from "../frontend/node_modules/playwright/index.mjs";

const prefix = `logscope-sample-${randomUUID().slice(0, 8)}`;
const containers = [],
  volumes = [];
const folder = await mkdtemp(join(tmpdir(), "logscope-sample-"));
let networkCreated = false,
  browser,
  pendingEvent,
  receiverUrl;
const isolatedLatencies = [];
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
function docker(args) {
  try {
    return execFileSync("docker", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  } catch (error) {
    throw new Error(
      `Isolated Docker step failed: ${args[0]}: ${error.stderr?.toString().slice(-1200) ?? ""}`,
    );
  }
}
function start(name, args) {
  const full = `${prefix}-${name}`;
  containers.push(full);
  docker(["run", "--detach", "--name", full, "--network", prefix, ...args]);
  return full;
}
function volume(name) {
  const full = `${prefix}-${name}`;
  docker(["volume", "create", full]);
  volumes.push(full);
  return full;
}
function port(name) {
  const match = /127\.0\.0\.1:(\d+)/.exec(docker(["port", name, "8080/tcp"]));
  assert.ok(match);
  return `http://127.0.0.1:${match[1]}`;
}
async function until(check, message, timeout = 120000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await pause(500);
  }
  throw new Error(message);
}
async function healthy(url) {
  await until(async () => {
    try {
      const r = await fetch(`${url}/actuator/health`, {
        signal: AbortSignal.timeout(2000),
      });
      return r.ok && (await r.json()).status === "UP";
    } catch {
      return false;
    }
  }, "Service readiness timed out");
}
async function post(url, body) {
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.equal(r.status, 200);
  const result = await r.json();
  if (result.eventId) pendingEvent = result;
  return result;
}
try {
  const password = randomUUID();
  await writeFile(
    join(folder, "db.env"),
    `MYSQL_DATABASE=logscope\nMYSQL_USER=logscope\nMYSQL_PASSWORD=${password}\nMYSQL_ROOT_PASSWORD=${randomUUID()}\n`,
    { mode: 0o600 },
  );
  await writeFile(
    join(folder, "app.env"),
    `SPRING_PROFILES_ACTIVE=local\nDB_URL=jdbc:mysql://db:3306/logscope?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true\nDB_USERNAME=logscope\nDB_PASSWORD=${password}\n`,
    { mode: 0o600 },
  );
  docker(["network", "create", prefix]);
  networkCreated = true;
  const db = start("db", [
    "--network-alias",
    "db",
    "--env-file",
    join(folder, "db.env"),
    "--health-cmd",
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -D logscope -e "SELECT 1"',
    "--health-interval",
    "2s",
    "--health-timeout",
    "2s",
    "--health-retries",
    "60",
    "mysql:8.4.13",
  ]);
  await until(
    async () =>
      docker(["inspect", "--format", "{{.State.Health.Status}}", db]) ===
      "healthy",
    "MySQL readiness timed out",
  );
  const app = start("app", [
    "--network-alias",
    "app",
    "--env-file",
    join(folder, "app.env"),
    "--publish",
    "127.0.0.1::8080",
    process.env.LOGSCOPE_VERIFY_IMAGE ?? "logscope:verify",
  ]);
  const api = port(app);
  await healthy(api);
  // Capture actual Vector envelopes while forwarding them to the real API/MySQL.
  const proxyCode = `const http=require('node:http');let batches=[],outcomes=[],outage=false,failures=0;http.createServer(async(q,s)=>{if(q.url==='/outcomes'){s.end(JSON.stringify(outcomes));return;}if(q.url==='/failures'){s.end(JSON.stringify(failures));return;}if(q.url==='/stats'){s.end(JSON.stringify(batches));return;}if(q.url==='/outage'){outage=q.method==='POST';s.end('{}');return;}if(outage){failures++;outage=false;s.writeHead(503);s.end();return;}try{let b='';for await(const c of q)b+=c;const a=JSON.parse(b);if(!Array.isArray(a))throw Error('array required');batches.push(a);const r=await fetch('http://app:8080/api/v1/logs/batch',{method:'POST',headers:{'Content-Type':'application/json'},body:b});const text=await r.text();let v;try{v=JSON.parse(text)}catch{}outcomes.push({status:r.status,stored:v?.stored??null,duplicates:v?.duplicates??null,rejected:v?.rejected?.map(x=>x.code)??null});s.writeHead(r.status);s.end(text);}catch{s.writeHead(500);s.end();}}).listen(8080,'0.0.0.0');`;
  const proxy = start("proxy", [
    "--no-healthcheck",
    "--network-alias",
    "receiver",
    "--publish",
    "127.0.0.1::8080",
    "--entrypoint",
    "node",
    process.env.LOGSCOPE_FRONTEND_VERIFY_IMAGE ?? "logscope-frontend:verify",
    "-e",
    proxyCode,
  ]);
  const receiver = port(proxy);
  receiverUrl = receiver;
  let config = await readFile("collectors/vector.yaml", "utf8");
  config = config.replace("http://app:8080", "http://receiver:8080");
  await writeFile(join(folder, "vector.yaml"), config);
  browser = await chromium.launch({ headless: true });
  const samples = [];
  for (let i = 1; i <= 2; i++) {
    const logs = volume(`logs-${i}`),
      data = volume(`vector-${i}`);
    const was = start(`was-${i}`, [
      "--env",
      `INSTANCE_ID=was-${i}`,
      "--env",
      "SAMPLE_LOG_DIR=/logs",
      "--env",
      "SAMPLE_LOG_MAX_SIZE=1KB",
      "--publish",
      "127.0.0.1::8080",
      "--volume",
      `${logs}:/logs`,
      process.env.LOGSCOPE_SAMPLE_VERIFY_IMAGE ?? "logscope-sample:verify",
    ]);
    const url = port(was);
    await healthy(url);
    const vector = start(`vector-${i}`, [
      "--env",
      "VECTOR_LOG=debug",
      "--volume",
      `${join(folder, "vector.yaml")}:/etc/vector/vector.yaml:ro`,
      "--volume",
      `${logs}:/logs:ro`,
      "--volume",
      `${data}:/var/lib/vector`,
      "timberio/vector:0.60.0-debian",
    ]);
    samples.push({ was, url, vector, i });
  }
  const ids = [];
  for (const sample of samples) {
    const page = await browser.newPage();
    await page.goto(sample.url);
    await page
      .locator("#instance")
      .filter({ hasText: `was-${sample.i}` })
      .waitFor();
    assert.equal(
      (await (await fetch(`${sample.url}/api/status`)).json()).intervalSeconds,
      30,
    );
    await page
      .locator('#scenario option[value="PAYMENT_TIMEOUT"]')
      .waitFor({ state: "attached" });
    await page.locator("#scenario").selectOption("PAYMENT_TIMEOUT");
    assert.equal(await page.locator("#level").inputValue(), "ERROR");
    await page.locator("#exception").click();
    await page.locator("#recent li").first().waitFor();
    const text = await page.locator("#recent li").first().textContent();
    const id = text.match(/[0-9a-f]{8}-[0-9a-f-]{27}/)?.[0];
    assert.ok(id);
    ids.push(id);
    const r = await fetch(`${sample.url}/api/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ level: null }),
    });
    assert.equal(r.status, 400);
    for (let n = 0; n < 40; n++)
      ids.push(
        (
          await post(`${sample.url}/api/events`, {
            level: ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"][n % 5],
            exception: false,
          })
        ).eventId,
      );
    await page.close();
  }
  const scenarioIds = [];
  for (const sample of samples) {
    const catalog = await (await fetch(`${sample.url}/api/scenarios`)).json();
    assert.equal(catalog.length, 20);
    for (const scenario of catalog) {
      const generated = await post(`${sample.url}/api/events`, {
        level: scenario.level,
        exception: scenario.level === "ERROR",
        scenario: scenario.code,
      });
      ids.push(generated.eventId);
      scenarioIds.push({
        id: generated.eventId,
        scenario,
        instanceId: `was-${sample.i}`,
      });
    }
    for (const input of [
      { level: "ERROR", scenario: "UNKNOWN" },
      { level: "INFO", scenario: "PAYMENT_TIMEOUT" },
      { level: "RANDOM", scenario: "PAYMENT_TIMEOUT" },
      { level: "WARN", exception: true },
    ]) {
      const invalid = await fetch(`${sample.url}/api/events`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input),
      });
      assert.equal(invalid.status, 400);
    }
  }
  for (const sample of samples)
    assert.match(
      docker(["exec", sample.was, "sh", "-c", "ls /logs/events.*.jsonl"]),
      /events\..*\.jsonl/,
    );
  async function stored(id) {
    const response = await fetch(`${api}/api/v1/logs/${id}`, {
      signal: AbortSignal.timeout(5000),
    });
    await response.arrayBuffer();
    return response.status === 200;
  }
  await until(
    async () => (await Promise.all(ids.map(stored))).every(Boolean),
    "Manual/rolled log did not reach MySQL",
  );
  for (const { id, scenario, instanceId } of scenarioIds) {
    const detail = await (await fetch(`${api}/api/v1/logs/${id}`)).json();
    assert.equal(detail.instanceId, instanceId);
    assert.equal(detail.level, scenario.level);
    assert.ok(detail.message.includes(`[${scenario.code}]`));
    assert.ok(detail.message.includes("[합성]"));
    assert.equal(
      typeof detail.exception === "string",
      scenario.level === "ERROR",
    );
  }
  for (const sample of samples)
    await post(`${sample.url}/api/automatic`, { enabled: false });
  // Regression: isolated low-volume events must arrive without another event waking the sink.
  for (let n = 0; n < 6; n++) {
    await pause(1100);
    const sample = samples[n % 2];
    const started = Date.now();
    const isolated = await post(`${sample.url}/api/events`, {
      level: "INFO",
      exception: false,
    });
    await until(
      () => stored(isolated.eventId),
      "Isolated event stalled without a subsequent write",
      30000,
    );
    isolatedLatencies.push(Date.now() - started);
  }
  for (const sample of samples)
    await post(`${sample.url}/api/automatic`, { enabled: true });
  for (const [index, id] of [
    [0, ids[0]],
    [1, ids[41]],
  ]) {
    const detail = await (await fetch(`${api}/api/v1/logs/${id}`)).json();
    assert.equal(detail.instanceId, `was-${index + 1}`);
    assert.equal(detail.level, "ERROR");
    assert.match(detail.message, /\[합성\]/);
    assert.match(
      detail.message,
      /PAYMENT_TIMEOUT.*결제 오류.*승인 여부 미확정/,
    );
    assert.match(detail.exception, /PaymentProcessingException/);
    assert.match(detail.exception, /SocketTimeoutException/);
  }
  await until(
    async () => {
      const batches = await (await fetch(`${receiver}/stats`)).json();
      return samples.every((s) =>
        batches
          .flat()
          .some(
            (e) =>
              e.instanceId === `was-${s.i}` && e.message.startsWith("[AUTO]"),
          ),
      );
    },
    "Default 30-second automatic logs did not arrive",
    55000,
  );
  for (const sample of samples)
    await post(`${sample.url}/api/automatic`, { enabled: false });
  const batches = await (await fetch(`${receiver}/stats`)).json();
  assert.ok(
    batches.some((b) => b.length > 1 && b.length <= 100),
    "Vector must send a real JSON batch",
  );
  // Persisted checkpoints survive a collector restart; subsequent records remain readable.
  docker(["restart", samples[0].vector]);
  const next = await post(`${samples[0].url}/api/events`, {
    level: "INFO",
    exception: false,
  });
  await until(
    () => stored(next.eventId),
    "Collector restart did not resume delivery",
  );
  // Transient receiver outage is retried using the same eventId.
  await fetch(`${receiver}/outage`, { method: "POST" });
  const retry = await post(`${samples[1].url}/api/events`, {
    level: "WARN",
    exception: false,
  });
  await until(
    async () => (await (await fetch(`${receiver}/failures`)).json()) > 0,
    "Outage must trigger a real failed request",
  );
  await fetch(`${receiver}/outage`, { method: "DELETE" });
  await until(() => stored(retry.eventId), "Transient failure did not recover");
  console.log(
    "Isolated event delivery milliseconds:",
    JSON.stringify(isolatedLatencies),
  );
  console.log(
    "Two WAS UI/manual/all-level/commerce scenarios/exception/30s automatic → Logback rotation → Vector JSON batch/checkpoint/retry → real MySQL integration passed.",
  );
} catch (error) {
  // Emit only status counts, never raw diagnostic headers, addresses, or payloads.
  for (const name of containers.filter((n) => n.includes("-vector-"))) {
    const result = spawnSync("docker", ["logs", name], { encoding: "utf8" });
    const log = (result.stdout ?? "") + (result.stderr ?? "");
    console.error(
      "Vector diagnostic:",
      JSON.stringify({
        statuses: [...log.matchAll(/HTTP response\. status=(\d+)/g)].map((m) =>
          Number(m[1]),
        ),
        retries: [...log.matchAll(/Retrying request\./g)].length,
      }),
    );
  }
  for (const name of containers.filter((n) => /-was-[12]$/.test(n))) {
    try {
      const raw = docker(["exec", name, "sh", "-c", "cat /logs/events*.jsonl"]);
      const rows = raw.split("\n").filter(Boolean);
      let invalid = 0,
        pendingWritten = false;
      for (const row of rows) {
        try {
          if (JSON.parse(row).eventId === pendingEvent?.eventId)
            pendingWritten = true;
        } catch {
          invalid++;
        }
      }
      console.error(
        "WAS file diagnostic:",
        JSON.stringify({ rows: rows.length, invalid, pendingWritten }),
      );
    } catch {
      console.error("WAS file diagnostic unavailable");
    }
  }
  if (receiverUrl) {
    try {
      console.error(
        "Batch outcomes:",
        JSON.stringify(
          await (
            await fetch(`${receiverUrl}/outcomes`, {
              signal: AbortSignal.timeout(2000),
            })
          ).json(),
        ),
      );
    } catch {
      console.error("Batch outcomes unavailable");
    }
  }
  console.error(
    "Completed isolated delivery milliseconds:",
    JSON.stringify(isolatedLatencies),
  );
  throw error;
} finally {
  await browser?.close();
  for (const name of containers.reverse()) {
    try {
      docker(["rm", "--force", "--volumes", name]);
    } catch {}
  }
  for (const name of volumes) {
    try {
      docker(["volume", "rm", name]);
    } catch {}
  }
  if (networkCreated) {
    try {
      docker(["network", "rm", prefix]);
    } catch {}
  }
  await rm(folder, { recursive: true, force: true });
}
