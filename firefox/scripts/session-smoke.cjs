const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const wasmDir = path.join(root, "src/wasm");

const glue = fs.readFileSync(path.join(wasmDir, "cipherkeep_wasm.js"), "utf8") +
  "\nglobalThis.wasm_bindgen = wasm_bindgen;";
vm.runInThisContext(glue, { filename: "cipherkeep_wasm.js" });

(async () => {
  const bytes = fs.readFileSync(path.join(wasmDir, "cipherkeep_wasm_bg.wasm"));
  await globalThis.wasm_bindgen(bytes);
  const Session = globalThis.wasm_bindgen.WasmVaultSession;

  const password = new TextEncoder().encode("correct horse battery staple");
  const created = Session.create(password);
  if (created.recovery_shares().length !== 3) {
    throw new Error("expected three recovery shares");
  }
  const blob = created.blob();

  const opened = Session.open(blob);
  if (!opened.is_locked()) {
    throw new Error("open must be locked");
  }
  opened.unlock_again(password);
  if (opened.is_locked()) {
    throw new Error("unlock_again failed");
  }
  const itemId = new Uint8Array(16).fill(7);
  opened.save_item(itemId, Buffer.from("payload"));
  if (opened.item_count() !== 1) {
    throw new Error("item count failed");
  }
  if (Buffer.from(opened.get_item(itemId)).toString() !== "payload") {
    throw new Error("item round-trip failed");
  }
  opened.delete_item(itemId);
  if (opened.item_count() !== 0) {
    throw new Error("item delete failed");
  }

  opened.lock();
  if (!opened.is_locked()) {
    throw new Error("lock failed");
  }

  let lockedCode = 0;
  try {
    opened.get_item(itemId);
  } catch (error) {
    lockedCode = error.code;
  }
  if (lockedCode !== 5) {
    throw new Error(`locked code was ${lockedCode}`);
  }

  let code = 0;
  try {
    opened.unlock_again(new TextEncoder().encode("wrong password value"));
  } catch (error) {
    code = error.code;
  }
  if (code !== 3) {
    throw new Error(`wrong-password code was ${code}`);
  }

  console.log("firefox wasm session smoke: PASS");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
