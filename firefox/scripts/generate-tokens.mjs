import { readFile, writeFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const data = JSON.parse(await readFile(resolve(root, "../design-tokens.json"), "utf8"));
const kebab = (value) => value.replace(/([A-Z])/g, (match) => `-${match.toLowerCase()}`);
const lines = [":root {"];
for (const theme of ["light", "dark"]) {
  for (const [name, token] of Object.entries(data.color[theme])) {
    lines.push(`  --ck-${theme}-${kebab(name)}: ${token.$value};`);
  }
}
for (const section of ["spacing", "radius"]) {
  for (const [name, token] of Object.entries(data[section])) {
    lines.push(`  --ck-${section}-${name}: ${token.$value};`);
  }
}
for (const [name, token] of Object.entries(data.sizing)) {
  if (token.$value) lines.push(`  --ck-size-${name}: ${token.$value};`);
}
for (const [name, token] of Object.entries(data.typography.scale)) {
  lines.push(`  --ck-type-${name}-size: ${token.$value.fontSize};`);
  lines.push(`  --ck-type-${name}-line: ${token.$value.lineHeight};`);
}
for (const [name, token] of Object.entries(data.motion.duration)) {
  lines.push(`  --ck-motion-${name}: ${token.$value};`);
}
lines.push("}");
const output = lines.join("\n") + "\n";
const path = resolve(root, "src/styles/tokens.css");
if (process.argv.includes("--check")) {
  const current = await readFile(path, "utf8");
  if (current !== output) {
    console.error("tokens.css is stale; run node scripts/generate-tokens.mjs");
    process.exit(1);
  }
  const kotlin = await readFile(resolve(root, "../android/app/src/main/java/dev/cipherkeep/app/ui/theme/Tokens.kt"), "utf8");
  for (const theme of ["light", "dark"]) {
    for (const [name, token] of Object.entries(data.color[theme])) {
      if (!String(token.$value).startsWith("#")) continue;
      const hex = String(token.$value).slice(1).toUpperCase();
      const prefix = theme === "light" ? "Light" : "Dark";
      const property = name.charAt(0).toUpperCase() + name.slice(1);
      if (!kotlin.includes(`${prefix}${property} = Color(0xFF${hex})`)) {
        console.error(`Tokens.kt is stale for ${theme}.${name}`);
        process.exit(1);
      }
    }
  }
} else {
  await writeFile(path, output, "utf8");
}
