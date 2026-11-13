import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dashboard = resolve(root, "dashboard");
const failures = [];

const latest = JSON.parse(await readFile(resolve(dashboard, "latest.json"), "utf8"));
if (latest.schema !== "cipherkeep-compat-v1") failures.push("bad schema");
if (!/^\d+\.\d+\.\d+/.test(latest.version)) failures.push("bad version");
if (typeof latest.recipeVersion !== "string" || latest.recipeVersion.length === 0) failures.push("bad recipeVersion");
if (Number.isNaN(Date.parse(latest.startedAt))) failures.push("bad startedAt");
if (!Array.isArray(latest.results) || latest.results.length === 0) failures.push("empty results");
for (const result of latest.results) {
  if (typeof result.id !== "string") failures.push("bad result id");
  if (!["pass", "fail"].includes(result.status)) failures.push(`bad status ${result.status}`);
}

const raw = await readFile(resolve(dashboard, "latest.json"), "utf8");
if (/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/.test(raw)) failures.push("email-like value in published data");
if (/(?:[A-Za-z]:\\|\/Users\/|\/home\/)/.test(raw)) failures.push("local path in published data");
if (/"password"\s*:|"secret"\s*:|"token"\s*:/i.test(raw)) failures.push("secret-like key in published data");

for (const name of ["index.html", "run-0.html"]) {
  const html = await readFile(resolve(dashboard, name), "utf8");
  if (!html.includes('lang="en"')) failures.push(`${name}: missing lang`);
  if (!html.includes("<caption>")) failures.push(`${name}: missing table caption`);
  if (!html.includes('scope="col"')) failures.push(`${name}: missing column scopes`);
  if (!html.includes('scope="row"')) failures.push(`${name}: missing row scope`);
  if (/<script\b/i.test(html)) failures.push(`${name}: script present`);
  if (/https?:\/\//i.test(html)) failures.push(`${name}: external request present`);
  if (/\son[a-z]+\s*=/i.test(html)) failures.push(`${name}: inline event handler present`);
}
for (const name of ["history.html"]) {
  const html = await readFile(resolve(dashboard, name), "utf8");
  if (!html.includes('lang="en"')) failures.push(`${name}: missing lang`);
  if (/<script\b/i.test(html)) failures.push(`${name}: script present`);
  if (/https?:\/\//i.test(html)) failures.push(`${name}: external request present`);
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`compat dashboard check: PASS (${latest.results.length} results)`);
