import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const root = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const out = await mkdtemp(join(tmpdir(), "ck-relay-"));
const bundle = join(out, "worker.mjs");
await build({
  entryPoints: [join(root, "..", "workers", "relay", "src", "index.ts")],
  bundle: true,
  format: "esm",
  platform: "neutral",
  outfile: bundle,
});
const token = "a".repeat(64);
const script = await readFile(bundle, "utf8");
const mf = new Miniflare({
  modules: true,
  script,
  r2Buckets: ["BUCKET"],
  bindings: { TOKEN: token },
});
const base = "http://relay.test";
const vault = "a".repeat(32);
const auth = { authorization: `Bearer ${token}` };
const fail = (message) => {
  throw new Error(message);
};
try {
  let response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`);
  if (response.status !== 401) fail(`unauth status ${response.status}`);
  response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`, {
    headers: { authorization: "Bearer wrong" },
  });
  if (response.status !== 401) fail(`wrong-token status ${response.status}`);
  response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`, {
    method: "PUT",
    headers: { ...auth, "content-type": "application/octet-stream", "content-length": "3" },
    body: new Uint8Array([1, 2, 3]),
  });
  if (response.status !== 201) fail(`put status ${response.status}`);
  response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`, { headers: auth });
  if (response.status !== 200) fail(`get status ${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length !== 3 || bytes[0] !== 1) fail("body mismatch");
  response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`, { method: "DELETE", headers: auth });
  if (response.status !== 204) fail(`delete status ${response.status}`);
  response = await mf.dispatchFetch(`${base}/v1/${vault}/manifest`, { headers: auth });
  if (response.status !== 404) fail(`post-delete status ${response.status}`);
  console.log("relay Miniflare CRUD/auth: PASS");
} finally {
  await mf.dispose();
  await rm(out, { recursive: true, force: true });
}
