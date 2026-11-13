import { createHash } from "node:crypto";
import { readFile, readdir, stat } from "node:fs/promises";
import { join, relative, resolve } from "node:path";

const [leftArg, rightArg] = process.argv.slice(2);
if (!leftArg || !rightArg) {
  console.error("usage: node scripts/compare-builds.mjs <left-dir> <right-dir>");
  process.exit(2);
}

const left = resolve(leftArg);
const right = resolve(rightArg);

async function walk(directory, root = directory) {
  const entries = [];
  for (const name of await readdir(directory)) {
    const path = join(directory, name);
    const info = await stat(path);
    if (info.isDirectory()) entries.push(...(await walk(path, root)));
    else entries.push({ path, name: relative(root, path).replaceAll("\\", "/") });
  }
  return entries;
}

const [leftFiles, rightFiles] = await Promise.all([walk(left), walk(right)]);
const leftMap = new Map(leftFiles.map((file) => [file.name, file.path]));
const rightMap = new Map(rightFiles.map((file) => [file.name, file.path]));

const names = [...new Set([...leftMap.keys(), ...rightMap.keys()])].sort();
let differences = 0;
for (const name of names) {
  const a = leftMap.get(name);
  const b = rightMap.get(name);
  if (!a || !b) {
    console.log(`only in ${a ? "left" : "right"}: ${name}`);
    differences += 1;
    continue;
  }
  const [leftBytes, rightBytes] = await Promise.all([readFile(a), readFile(b)]);
  const leftHash = createHash("sha256").update(leftBytes).digest("hex");
  const rightHash = createHash("sha256").update(rightBytes).digest("hex");
  if (leftHash !== rightHash) {
    console.log(`different: ${name}`);
    differences += 1;
  }
}

if (differences === 0) {
  console.log(`reproducible: ${names.length} files identical`);
} else {
  console.error(`${differences} difference(s) in ${names.length} files`);
  process.exit(1);
}
