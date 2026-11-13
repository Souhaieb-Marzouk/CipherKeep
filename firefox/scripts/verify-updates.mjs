import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index === -1 ? fallback : process.argv[index + 1];
}

function compareSemver(left, right) {
  const parse = (value) => value.split("-")[0].split(".").map(Number);
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] > b[index] ? 1 : -1;
  }
  return 0;
}

const updatesPath = resolve(argument("updates", "updates.json"));
const xpiPath = resolve(argument("xpi", ""));
const expectedVersion = argument("version", "");

const raw = await readFile(updatesPath, "utf8");
if (/placeholder|example\.com|TODO|CHANGEME/i.test(raw)) {
  throw new Error("updates.json contains a placeholder");
}
const updates = JSON.parse(raw);
const addon = updates.addons?.["cipherkeep@cipherkeep.dev"];
if (addon === undefined) throw new Error("missing addon entry");
if (!Array.isArray(addon.updates) || addon.updates.length !== 1) {
  throw new Error("expected exactly one update");
}
const update = addon.updates[0];
if (compareSemver(update.version, expectedVersion) !== 0) {
  throw new Error(`version mismatch: ${update.version} != ${expectedVersion}`);
}
if (!/^https:\/\/.+\.xpi$/.test(update.update_link)) {
  throw new Error("update_link is not an HTTPS XPI URL");
}
if (!/^sha256:[0-9a-f]{64}$/.test(update.update_hash)) {
  throw new Error("update_hash is not sha256:<hex>");
}

const bytes = await readFile(xpiPath);
const actual = `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
if (actual !== update.update_hash) {
  throw new Error(`update_hash mismatch: ${actual} != ${update.update_hash}`);
}
console.log(`updates.json verification: PASS (${update.version})`);
