import { build } from "esbuild";
import { cp, mkdir, readFile, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const dist = resolve(root, "dist");
const wasmGlue = await readFile(resolve(root, "src/wasm/cipherkeep_wasm.js"), "utf8");

await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });

const shared = {
  bundle: true,
  sourcemap: false,
  minify: false,
  target: "firefox109",
  legalComments: "none",
  logLevel: "info",
};

await build({
  ...shared,
  banner: { js: wasmGlue },
  entryPoints: [resolve(root, "src/background/index.ts")],
  outfile: resolve(dist, "background.js"),
  format: "iife",
});

await build({
  ...shared,
  entryPoints: [resolve(root, "src/popup/index.ts")],
  outfile: resolve(dist, "popup.js"),
  format: "iife",
});

await build({
  ...shared,
  entryPoints: [resolve(root, "src/options/index.ts")],
  outfile: resolve(dist, "options.js"),
  format: "iife",
});

await build({
  ...shared,
  entryPoints: [resolve(root, "src/content/index.ts")],
  outfile: resolve(dist, "content.js"),
  format: "iife",
});

await cp(resolve(root, "manifest.json"), resolve(dist, "manifest.json"));
await cp(resolve(root, "src/popup/index.html"), resolve(dist, "popup.html"));
await cp(resolve(root, "src/options/index.html"), resolve(dist, "options.html"));
await cp(resolve(root, "src/icons"), resolve(dist, "icons"), { recursive: true });
await cp(
  resolve(root, "src/wasm/cipherkeep_wasm_bg.wasm"),
  resolve(dist, "cipherkeep_wasm_bg.wasm"),
);

console.log("Firefox extension built in dist/");
