import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const data = JSON.parse(await readFile(resolve(root, "../design-tokens.json"), "utf8"));

function luminance(hex) {
  const value = hex.replace("#", "");
  const channels = [0, 2, 4].map((offset) => Number.parseInt(value.slice(offset, offset + 2), 16) / 255);
  const linear = channels.map((channel) =>
    channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(foreground, background) {
  const first = luminance(foreground);
  const second = luminance(background);
  const light = Math.max(first, second);
  const dark = Math.min(first, second);
  return (light + 0.05) / (dark + 0.05);
}

const pairs = [
  ["light", "text", "background", 4.5],
  ["light", "textMuted", "background", 4.5],
  ["light", "onPrimary", "primary", 4.5],
  ["light", "dangerText", "background", 4.5],
  ["light", "borderStrong", "background", 3.0],
  ["dark", "text", "background", 4.5],
  ["dark", "textMuted", "background", 4.5],
  ["dark", "onPrimary", "primary", 4.5],
  ["dark", "dangerText", "background", 4.5],
  ["dark", "borderStrong", "background", 3.0],
];

let failed = false;
for (const [theme, foreground, background, minimum] of pairs) {
  const ratio = contrast(
    data.color[theme][foreground].$value,
    data.color[theme][background].$value,
  );
  if (ratio < minimum) {
    failed = true;
    console.error(`${theme}.${foreground} on ${background}: ${ratio.toFixed(2)} < ${minimum}`);
  }
}
if (failed) {
  process.exit(1);
}
console.log("WCAG contrast pairs: PASS");
