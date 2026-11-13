import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index === -1 ? fallback : process.argv[index + 1];
}

const xpi = resolve(argument("xpi", ""));
const version = argument("version", "");
const updateLink = argument("url", "");
const output = resolve(argument("out", "updates.json"));
const addonId = "cipherkeep@cipherkeep.dev";

if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) {
  throw new Error("--version must be semver");
}
if (!updateLink.startsWith("https://") || !updateLink.endsWith(".xpi")) {
  throw new Error("--url must be an HTTPS .xpi asset URL");
}
if (!updateLink.includes(`/${version}/`) && !updateLink.includes(`-${version}.xpi`)) {
  throw new Error("update URL does not contain the release version");
}

const bytes = await readFile(xpi);
if (bytes.length === 0) throw new Error("empty XPI");
const hash = createHash("sha256").update(bytes).digest("hex");

const updates = {
  addons: {
    [addonId]: {
      updates: [
        {
          version,
          update_link: updateLink,
          update_hash: `sha256:${hash}`,
        },
      ],
    },
  },
};

await writeFile(output, `${JSON.stringify(updates, null, 2)}\n`, "utf8");
console.log(`updates.json ${version} sha256:${hash}`);
