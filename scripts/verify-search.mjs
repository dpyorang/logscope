import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

const scope = `logscope-search-${randomUUID().slice(0, 8)}`;
const folder = await mkdtemp(join(tmpdir(), "logscope-search-"));
const containers = [];
let network = false;
let paused = null;
function docker(args) {
  try { return execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim(); }
  catch { throw new Error(`Search integration Docker step failed: ${args[0]}`); }
}
function start(label, args) {
  const name = `${scope}-${label}`;
  containers.push(name);
  docker(["run", "--detach", "--name", name, "--network", scope, ...args]);
  return name;
}
function address(name, port) {
  const match = /127\.0\.0\.1:(\d+)/.exec(docker(["port", name, `${port}/tcp`]));
  assert.ok(match, "Isolated port mapping required");
  return `http://127.0.0.1:${match[1]}`;
}
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(check, label) {
  const end = Date.now() + 120000;
  while (Date.now() < end) {
    try { if (await check()) return; } catch { /* bounded retry for startup or indexing */ }
    await pause(500);
  }
  throw new Error(`Search integration timed out: ${label}`);
}
async function request(url, options = {}) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(8000) });
}
try {
  const password = randomUUID();
  await writeFile(join(folder, "db.env"), `MYSQL_DATABASE=logscope\nMYSQL_USER=logscope\nMYSQL_PASSWORD=${password}\nMYSQL_ROOT_PASSWORD=${randomUUID()}\n`, { mode: 0o600 });
  await writeFile(join(folder, "app.env"), `SPRING_PROFILES_ACTIVE=local\nDB_URL=jdbc:mysql://db:3306/logscope?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true\nDB_USERNAME=logscope\nDB_PASSWORD=${password}\nSEARCH_ENABLED=true\nSEARCH_URL=http://search:9200\n`, { mode: 0o600 });
  docker(["network", "create", scope]); network = true;
  const db = start("db", ["--network-alias", "db", "--env-file", join(folder, "db.env"), "--health-cmd", 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -D logscope -e "SELECT 1"', "--health-interval", "2s", "--health-timeout", "2s", "--health-retries", "60", "mysql:8.4.13"]);
  const search = start("engine", ["--network-alias", "search", "--memory", "2g", "--env", "discovery.type=single-node", "--env", "xpack.security.enabled=false", "--env", "action.auto_create_index=false", "--env", "ES_JAVA_OPTS=-Xms512m -Xmx512m", "--publish", "127.0.0.1::9200", process.env.LOGSCOPE_SEARCH_VERIFY_IMAGE ?? "logscope-search:verify"]);
  const engine = address(search, 9200);
  await until(() => docker(["inspect", "--format", "{{.State.Health.Status}}", db]) === "healthy", "MySQL readiness");
  await until(async () => { const r = await request(`${engine}/_cluster/health`); return r.ok && ["yellow", "green"].includes((await r.json()).status); }, "Elasticsearch readiness");
  const app = start("app", ["--env-file", join(folder, "app.env"), "--publish", "127.0.0.1::8080", process.env.LOGSCOPE_VERIFY_IMAGE ?? "logscope:verify"]);
  const api = address(app, 8080);
  await until(async () => { const r = await request(`${api}/actuator/health/readiness`); return r.ok && (await r.json()).status === "UP"; }, "application readiness");
  const range = new URLSearchParams({ from: new Date(Date.now() - 3600000).toISOString(), to: new Date(Date.now() + 3600000).toISOString(), level: "ERROR", instanceId: "was-2", keyword: "결제 오류" });
  const make = (instanceId, level, message) => ({ eventId: randomUUID(), occurredAt: new Date(Date.now() - 1000).toISOString(), serviceName: "sample-was", instanceId, level, message });
  const first = make("was-2", "ERROR", "결제 처리 중 오류가 발생했습니다");
  for (const value of [first, make("was-1", "ERROR", "결제 오류"), make("was-2", "INFO", "결제 오류"), make("was-2", "ERROR", "결제 완료")]) {
    const r = await request(`${api}/api/v1/logs`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(value) }); assert.equal(r.status, 201);
  }
  async function indexed(count) {
    const r = await request(`${api}/api/v1/logs/search?${range}`);
    if (!r.ok) return false;
    const body = await r.json();
    return body.items.length === count && body.synchronization.pendingCount === 0;
  }
  await until(() => indexed(1), "scheduled Nori indexing and filters");
  docker(["pause", search]); paused = search;
  const delayed = make("was-2", "ERROR", "결제 오류 재시도");
  assert.equal((await request(`${api}/api/v1/logs`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(delayed) })).status, 201);
  assert.equal((await request(`${api}/api/v1/logs/search?${range}`)).status, 503);
  assert.equal((await request(`${api}/api/v1/logs/${delayed.eventId}`)).status, 200);
  docker(["unpause", search]); paused = null;
  await until(() => indexed(2), "durable queue recovery");
  assert.equal((await request(`${api}/api/v1/logs`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(first) })).status, 200);
  assert.equal((await request(`${engine}/logscope-logs-v1`, { method: "DELETE" })).status, 200);
  await until(() => indexed(2), "lost index recovery from MySQL");
  console.log("Isolated MySQL outbox → scheduled Nori Elasticsearch search/filter/outage/rebuild integration passed.");
} finally {
  if (paused) { try { docker(["unpause", paused]); } catch {} }
  for (const name of containers.reverse()) { try { docker(["rm", "--force", "--volumes", name]); } catch {} }
  if (network) { try { docker(["network", "rm", scope]); } catch {} }
  await rm(folder, { recursive: true, force: true });
}
