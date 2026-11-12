import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const manifest = JSON.parse(await readFile(resolve(root, "dist/manifest.json"), "utf8"));

if (manifest.manifest_version !== 3) {
  throw new Error("manifest_version must be 3");
}
if (!manifest.name || !manifest.version) {
  throw new Error("manifest name/version missing");
}
const csp = manifest.content_security_policy?.extension_pages ?? "";
const scriptSrc = csp.split(";").map((part) => part.trim()).find((part) => part.startsWith("script-src")) ?? "";
if (/https?:\/\//i.test(scriptSrc) || /'unsafe-eval'|'unsafe-inline'/i.test(scriptSrc)) {
  throw new Error("extension CSP permits remote or unsafe code");
}
for (const resource of manifest.web_accessible_resources ?? []) {
  for (const match of resource.matches ?? []) {
    if (match === "<all_urls>" || match === "*://*/*") {
      throw new Error("web_accessible_resources exposes all origins");
    }
  }
}
if ((manifest.permissions ?? []).includes("<all_urls>")) {
  throw new Error("broad host permission present");
}
console.log("extension manifest/CSP lint: PASS");
