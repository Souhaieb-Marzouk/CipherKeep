import { readFile, writeFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const manifestPath = resolve(root, "manifest.json");

function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index === -1 ? fallback : process.argv[index + 1];
}

const version = argument("version", "");
const updateUrl = argument("update-url", "");
if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) {
  throw new Error("--version must be semver, for example 0.1.0");
}
if (updateUrl !== "" && !updateUrl.startsWith("https://")) {
  throw new Error("--update-url must be an HTTPS URL");
}

const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
if (manifest.browser_specific_settings?.gecko?.id !== "cipherkeep@cipherkeep.dev") {
  throw new Error("unexpected extension ID");
}
manifest.version = version;
if (updateUrl === "") {
  delete manifest.browser_specific_settings.gecko.update_url;
} else {
  manifest.browser_specific_settings.gecko.update_url = updateUrl;
}

await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
console.log(`manifest version=${version}`);
console.log(`manifest update_url=${updateUrl}`);
