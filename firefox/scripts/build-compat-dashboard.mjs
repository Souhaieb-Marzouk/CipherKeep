import { readFile, readdir, writeFile, mkdir } from "node:fs/promises";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = JSON.parse(await readFile(resolve(root, "tests/compat/fixtures.json"), "utf8"));
const historyDir = resolve(root, "tests/compat/history");
const output = resolve(root, "dashboard");

const escapeHtml = (value) =>
  String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]);

function normalize(value) {
  if (Array.isArray(value)) {
    return {
      schema: "cipherkeep-compat-v1",
      version: "0.1.0",
      recipeVersion: "1",
      startedAt: new Date().toISOString(),
      browser: "chromium",
      results: value,
    };
  }
  if (value?.schema !== "cipherkeep-compat-v1" || !Array.isArray(value.results)) {
    throw new Error("invalid compatibility result document");
  }
  return value;
}

const runs = [];
try {
  runs.push(normalize(JSON.parse(await readFile(resolve(root, "tests/compat/results.json"), "utf8"))));
} catch {}
try {
  for (const name of await readdir(historyDir)) {
    if (!name.endsWith(".json")) continue;
    runs.push(normalize(JSON.parse(await readFile(join(historyDir, name), "utf8"))));
  }
} catch {}
if (runs.length === 0) throw new Error("no compatibility runs found");

const unique = new Map();
for (const run of runs) {
  const key = `${run.startedAt}:${run.version}:${run.recipeVersion}`;
  if (!unique.has(key)) unique.set(key, run);
}
const ordered = [...unique.values()].sort((a, b) => String(b.startedAt).localeCompare(String(a.startedAt)));
const latest = ordered[0];

const css = `
body{font:16px/1.5 system-ui,sans-serif;max-width:72rem;margin:2rem auto;padding:0 1rem;color:#0f172a;background:#fff}
a{color:#1d4ed8}table{border-collapse:collapse;width:100%;margin:1rem 0}
caption{text-align:left;font-weight:600;margin:.5rem 0}th,td{border:1px solid #475569;padding:.5rem;text-align:left;vertical-align:top}
th{background:#e2e8f0}.pass{color:#166534;font-weight:700}.fail{color:#991b1b;font-weight:700}.pending{color:#92400e;font-weight:700}
nav{display:flex;gap:1rem;flex-wrap:wrap}.meta{color:#334155}
`;

function table(run) {
  const status = new Map(run.results.map((result) => [result.id, result]));
  const rows = fixtures.map((fixture) => {
    const result = status.get(fixture.id);
    const state = result?.status ?? "pending";
    return `<tr><th scope="row">${escapeHtml(fixture.id)}</th><td>${escapeHtml(fixture.pattern)}</td><td>${fixture.shadow ? "yes" : "no"}</td><td>${fixture.iframe ? "yes" : "no"}</td><td>${escapeHtml(fixture.expected)}</td><td class="${escapeHtml(state)}">${escapeHtml(state.toUpperCase())}</td><td>${result ? (result.filled ? "filled" : "not filled") : "not run"}</td></tr>`;
  }).join("\n");
  return `<table><caption>${escapeHtml(fixtureCaption(run))}</caption><thead><tr><th scope="col">Fixture</th><th scope="col">Pattern</th><th scope="col">Shadow DOM</th><th scope="col">Iframe</th><th scope="col">Expected</th><th scope="col">Status</th><th scope="col">Observed</th></tr></thead><tbody>${rows}</tbody></table>`;
}

function fixtureCaption(run) {
  return `Compatibility run ${run.version} recipe ${run.recipeVersion} at ${run.startedAt}`;
}

function page(title, body) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><title>${escapeHtml(title)}</title><style>${css}</style></head><body><nav><a href="index.html">Latest</a><a href="history.html">History</a></nav><main>${body}</main></body></html>`;
}

await mkdir(output, { recursive: true });
const index = `<h1>CipherKeep autofill compatibility</h1><p class="meta">Latest run: ${escapeHtml(latest.version)}, recipe ${escapeHtml(latest.recipeVersion)}, ${escapeHtml(latest.startedAt)}.</p><p>Deterministic fixtures only. No real credentials, user paths or secrets are published.</p>${table(latest)}<p><a href="history.html">All runs</a></p>`;
await writeFile(join(output, "index.html"), page("CipherKeep autofill compatibility", index), "utf8");

const links = ordered.map((run, index) => {
  const state = run.results.some((result) => result.status === "fail") ? "FAIL" : "PASS";
  return `<li><a href="run-${index}.html">${escapeHtml(run.startedAt)} — ${escapeHtml(run.version)} — recipe ${escapeHtml(run.recipeVersion)}</a> <span class="${state === "FAIL" ? "fail" : "pass"}">${state}</span></li>`;
}).join("\n");
await writeFile(join(output, "history.html"), page("Compatibility history", `<h1>Compatibility history</h1><p class="meta">${ordered.length} retained run(s).</p><ul>${links}</ul>`), "utf8");

for (const [index, run] of ordered.entries()) {
  const body = `<h1>Compatibility run</h1><p class="meta">${escapeHtml(run.version)}, recipe ${escapeHtml(run.recipeVersion)}, ${escapeHtml(run.startedAt)}, ${escapeHtml(run.browser)}.</p>${table(run)}`;
  await writeFile(join(output, `run-${index}.html`), page("Compatibility run", body), "utf8");
}

await writeFile(join(output, "latest.json"), JSON.stringify(latest, null, 2), "utf8");
await writeFile(join(output, "history.json"), JSON.stringify(ordered, null, 2), "utf8");
console.log(`compat dashboard: ${ordered.length} run(s), ${fixtures.length} fixture(s)`);
