import { readFile, readdir, access } from "node:fs/promises";
import { resolve, dirname, join } from "node:path";

const root = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")), "..");
const docs = [
  "README.md",
  "SECURITY.md",
  ...(await readdir(join(root, "docs")))
    .filter((name) => name.endsWith(".md"))
    .map((name) => `docs/${name}`),
];

const placeholder = /REPLACE_WITH|TODO:|TBD|CHANGEME|example\.com|PLACEHOLDER/;
let failures = 0;

for (const file of docs) {
  const path = join(root, file);
  const text = await readFile(path, "utf8");
  if (placeholder.test(text)) {
    console.error(`${file}: placeholder text found`);
    failures += 1;
  }
  for (const match of text.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
    const target = match[1].split("#")[0];
    if (target === "" || /^[a-z][a-z0-9+.-]*:/i.test(target)) continue;
    const resolved = resolve(dirname(path), target);
    try {
      await access(resolved);
    } catch {
      console.error(`${file}: broken relative link ${target}`);
      failures += 1;
    }
  }
}

if (failures > 0) {
  console.error(`${failures} documentation problem(s)`);
  process.exit(1);
}
console.log(`documentation check: PASS (${docs.length} files)`);
