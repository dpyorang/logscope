import { readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";

const pkg = JSON.parse(
  await readFile(new URL("../package.json", import.meta.url), "utf8"),
);
const npm = execFileSync("npm", ["--version"], { encoding: "utf8" }).trim();
if (process.versions.node !== pkg.engines.node || npm !== pkg.engines.npm) {
  throw new Error(
    `Required Node ${pkg.engines.node} / npm ${pkg.engines.npm}; found Node ${process.versions.node} / npm ${npm}`,
  );
}
console.log(`Runtime verified: Node ${process.versions.node}, npm ${npm}`);
