import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const dist = fileURLToPath(new URL("../dist/", import.meta.url));
const manifest = JSON.parse(await readFile(join(dist, "manifest.json"), "utf8"));

if (manifest.manifest_version !== 3) {
  throw new Error("manifest_version must be 3");
}
if (manifest.host_permissions) {
  throw new Error("host_permissions must not be present at install");
}
const csp = manifest.content_security_policy?.extension_pages ?? "";
for (const required of ["script-src 'self'", "object-src 'none'", "base-uri 'none'", "frame-ancestors 'none'"]) {
  if (!csp.includes(required)) {
    throw new Error(`CSP is missing: ${required}`);
  }
}
if (!csp.includes("'wasm-unsafe-eval'")) {
  throw new Error("CSP must allow the bundled WASM module");
}

for (const file of await readdir(dist)) {
  if (!file.endsWith(".js")) {
    continue;
  }
  const source = await readFile(join(dist, file), "utf8");
  for (const pattern of [/\beval\s*\(/, /new\s+Function\s*\(/, /\bimport\s*\(/]) {
    if (pattern.test(source)) {
      throw new Error(`${file} contains a forbidden dynamic-code pattern: ${pattern}`);
    }
  }
}

console.log("manifest/CSP scan: PASS");
