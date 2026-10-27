import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
const prefix = `logscope-ui-${randomUUID().slice(0,8)}`;
const containers = [];
const folder = await mkdtemp(join(tmpdir(), "logscope-ui-"));
let networkCreated = false;
function docker(args) { try { return execFileSync("docker", args, {encoding:"utf8",stdio:["ignore","pipe","pipe"]}).trim(); } catch { throw new Error(`Docker integration step failed: ${args[0]}`); } }
function start(name,args) { const full = `${prefix}-${name}`; containers.push(full); docker(["run","--detach","--name",full,"--network",prefix,...args]); return full; }
function port(name,internal) { const output=docker(["port",name,`${internal}/tcp`]); const match=/127\.0\.0\.1:(\d+)/.exec(output); if(!match) throw new Error("Isolated port mapping unavailable"); return `http://127.0.0.1:${match[1]}`; }
async function ready(url) { const end=Date.now()+120000; while(Date.now()<end) { try { const r=await fetch(url,{signal:AbortSignal.timeout(2000)}); const body=await r.json(); if(r.ok && body.status==="UP") return; } catch {} await new Promise(resolve=>setTimeout(resolve,1000)); } throw new Error("Isolated service readiness timed out"); }
try {
 const password=randomUUID(); const rootPassword=randomUUID();
 await writeFile(join(folder,"db.env"),`MYSQL_DATABASE=logscope\nMYSQL_USER=logscope\nMYSQL_PASSWORD=${password}\nMYSQL_ROOT_PASSWORD=${rootPassword}\n`,{mode:0o600});
 await writeFile(join(folder,"app.env"),`SPRING_PROFILES_ACTIVE=local\nDB_URL=jdbc:mysql://db:3306/logscope?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true\nDB_USERNAME=logscope\nDB_PASSWORD=${password}\n`,{mode:0o600});
 docker(["network","create",prefix]); networkCreated=true;
 const db=start("db",["--network-alias","db","--env-file",join(folder,"db.env"),"--health-cmd",'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -D logscope -e "SELECT 1"',"--health-interval","2s","--health-timeout","2s","--health-retries","60","mysql:8.4.13"]);
 const end=Date.now()+120000; while(docker(["inspect","--format","{{.State.Health.Status}}",db])!=="healthy") { if(Date.now()>end) throw new Error("Isolated MySQL readiness timed out"); await new Promise(resolve=>setTimeout(resolve,1000)); }
 const app=start("app",["--network-alias","app","--env-file",join(folder,"app.env"),"--publish","127.0.0.1::8080","logscope:verify"]);
 const api=port(app,8080); await ready(`${api}/actuator/health/readiness`);
 const front=start("frontend",["--env","LOGSCOPE_API_BASE_URL=http://app:8080","--publish","127.0.0.1::3000","logscope-frontend:verify"]);
 const ui=port(front,3000); await ready(`${ui}/health/live`);
 const code=await new Promise((resolve,reject)=>{ const child=spawn("npm",["exec","--no","--","playwright","test","--config","playwright.integration.config.ts"],{cwd:"frontend",env:{...process.env,LOGSCOPE_TEST_UI_URL:ui,LOGSCOPE_TEST_API_URL:api},stdio:"inherit"}); child.on("error",reject); child.on("exit",resolve); });
 if(code!==0) throw new Error("Persisted log browser integration failed");
 console.log("Isolated MySQL → Spring Boot → Next.js SSR/detail integration passed.");
} finally {
 for(const name of containers.reverse()) { try { docker(["rm","--force","--volumes",name]); } catch {} }
 if(networkCreated) { try { docker(["network","rm",prefix]); } catch {} }
 await rm(folder,{recursive:true,force:true});
}
