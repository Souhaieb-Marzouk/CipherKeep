import { readFile, writeFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = JSON.parse(await readFile(resolve(root, "tests/compat/fixtures.json"), "utf8"));
let results = [];
try {
  results = JSON.parse(await readFile(resolve(root, "tests/compat/results.json"), "utf8"));
} catch {}
const status = new Map(results.map((result) => [result.id, result.status]));
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
const rows = fixtures.map((fixture) => `<tr><td>${escapeHtml(fixture.id)}</td><td>${escapeHtml(fixture.pattern)}</td><td>${escapeHtml(fixture.shadow)}</td><td>${escapeHtml(fixture.iframe)}</td><td>${escapeHtml(fixture.expected)}</td><td>${escapeHtml(status.get(fixture.id) ?? "pending CI")}</td></tr>`).join("\n");
const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>CipherKeep autofill compatibility</title><style>body{font:16px system-ui;margin:2rem}table{border-collapse:collapse;width:100%}th,td{border:1px solid #64748B;padding:.5rem;text-align:left}th{background:#F1F5F9}</style></head><body><main><h1>Autofill compatibility matrix</h1><p>Deterministic fixtures; no real credentials. CI fills the status column after Playwright runs.</p><table><thead><tr><th>Fixture</th><th>Pattern</th><th>Shadow DOM</th><th>Iframe</th><th>Expected</th><th>Status</th></tr></thead><tbody>${rows}</tbody></table></main></body></html>`;
await writeFile(resolve(root, "tests/compat/dashboard.html"), html, "utf8");
console.log("compat dashboard generated");
