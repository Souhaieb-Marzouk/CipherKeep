import { createServer } from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const port = 4173;
const base = `http://127.0.0.1:${port}`;

const pages = {
  "/plain": `<!doctype html><form id="f"><input id="username" name="username"><input id="password" type="password"><button type="submit">Login</button></form><script>window.submitted=false;document.getElementById('f').addEventListener('submit',e=>{e.preventDefault();window.submitted=true})</script>`,
  "/shadow": `<!doctype html><div id="host"></div><script>const r=document.getElementById('host').attachShadow({mode:'open'});r.innerHTML='<form id="f"><input id="user" name="user"><input id="pass" type="password"><button type="submit">Login</button></form>';window.submitted=false;r.getElementById('f').addEventListener('submit',e=>{e.preventDefault();window.submitted=true})</script>`,
  "/spa": `<!doctype html><div id="app"></div><script>window.submitted=false;setTimeout(()=>{document.getElementById('app').innerHTML='<form id="f"><input id="email" type="email"><input id="pass" type="password"><button type="submit">Login</button></form>';document.getElementById('f').addEventListener('submit',e=>{e.preventDefault();window.submitted=true})},50)</script>`,
  "/hidden": `<!doctype html><form id="f"><input id="user" style="display:none"><input id="pass" type="password" style="visibility:hidden"><button type="submit">Login</button></form>`,
  "/iframe": `<!doctype html><iframe src="${base}/plain"></iframe>`,
};

const server = createServer(async (request, response) => {
  const path = request.url ?? "/";
  response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  response.end(pages[path] ?? pages["/plain"]);
});
await new Promise((resolveListen) => server.listen(port, "127.0.0.1", resolveListen));

const browser = await chromium.launch({ channel: "msedge", headless: true });
const context = await browser.newContext();

async function inspect(path, originAllowed) {
  const page = await context.newPage();
  await page.goto(`${base}${path}`);
  await page.waitForTimeout(120);
  const result = await page.evaluate((allowed) => {
    const roots = [document];
    for (let index = 0; index < roots.length && index < 8; index += 1) {
      for (const element of Array.from(roots[index].querySelectorAll("*"))) {
        if (element.shadowRoot) roots.push(element.shadowRoot);
      }
    }
    const visible = (input) => {
      if (!input || input.offsetParent === null && input.type !== "hidden") return false;
      if (input.disabled || input.readOnly) return false;
      if (input.getAttribute("aria-hidden") === "true") return false;
      const style = getComputedStyle(input);
      return style.display !== "none" && style.visibility === "visible" && Number(style.opacity) >= 0.1;
    };
    let password = null;
    let username = null;
    for (const root of roots) {
      for (const input of Array.from(root.querySelectorAll("input"))) {
        if (!visible(input)) continue;
        if (input.type === "password" && !password) password = input;
        else if (!username && ["text", "email", "tel"].includes(input.type)) username = input;
      }
    }
    if (allowed && password) {
      if (username) { username.value = "dummy-user"; username.dispatchEvent(new Event("input", { bubbles: true })); }
      password.value = "dummy-pass";
      password.dispatchEvent(new Event("input", { bubbles: true }));
    }
    return {
      filled: Boolean(allowed && password),
      submitted: Boolean(window.submitted),
      hasPassword: Boolean(password),
    };
  }, originAllowed);
  await page.close();
  return result;
}

const results = [];
for (const fixture of [
  ["plain-login", "/plain", true],
  ["shadow-login", "/shadow", true],
  ["spa-login", "/spa", true],
  ["hidden-field", "/hidden", true],
  ["lookalike-origin", "/plain", false],
  ["cross-origin-iframe", "/iframe", true],
]) {
  const [id, path, allowed] = fixture;
  const result = await inspect(path, allowed);
  const pass =
    id === "hidden-field" || id === "cross-origin-iframe"
      ? !result.filled
      : id === "lookalike-origin"
        ? !result.filled
        : result.filled && !result.submitted;
  results.push({ id, status: pass ? "pass" : "fail", ...result });
}

await browser.close();
server.close();
await writeFile(resolve(root, "tests/compat/results.json"), JSON.stringify(results, null, 2), "utf8");
console.log(results);
if (results.some((entry) => entry.status !== "pass")) process.exit(1);
