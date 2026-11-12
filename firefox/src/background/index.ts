import { isRequest, type ItemView, type Request, type Response, type SearchHit } from "../shared/protocol";
import { CiphertextStore } from "../shared/ciphertext-store";
import { backoffSeconds, remainingSeconds } from "../shared/backoff";
import { checkForUpdates } from "./updates";
import { recipeForOrigin, verifyRegistry, type RecipeRegistry } from "../shared/recipes";
import { RECIPE_PUBLIC_KEY } from "../shared/recipe-key";
import { EMPTY_SYNC_CONFIG, validateSyncConfig, type SyncConfig } from "../shared/sync-config";
import { GitHubTransport, RelayTransport, WebDavTransport } from "../shared/sync-transport";

let session: WasmVaultSession | null = null;
let vaultBlob: string | null = null;
let shares: string[] = [];
let searchIndex: WasmSearchIndex | null = null;
let recipeRegistry: RecipeRegistry | null = null;
let recipeHash = "";
const autofillTokens = new Map<string, {
  username: string;
  password: string;
  usernameSelector?: string;
  passwordSelector?: string;
  expiresAt: number;
}>();
let wasmReady: Promise<unknown> | null = null;
const LOCK_ALARM = "cipherkeep-auto-lock";
const itemStore = new CiphertextStore();
const UNLOCK_FAILED = "unlockFailedAttempts";
const UNLOCK_NEXT = "unlockNextAttemptAt";
const FOLDER_META_ID = "f".repeat(32);
let itemStoreReady: Promise<void> | null = null;

function ensureWasm(): Promise<unknown> {
  wasmReady ??= wasm_bindgen(browser.runtime.getURL("cipherkeep_wasm_bg.wasm"));
  return wasmReady;
}

function ensureItemStore(): Promise<void> {
  itemStoreReady ??= itemStore.open();
  return itemStoreReady;
}

function hostOf(value: string): string | null {
  try {
    return new URL(value).hostname;
  } catch {
    return null;
  }
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex: string): Uint8Array {
  if (hex.length % 2 !== 0) {
    throw new Error("invalid id");
  }
  const bytes = new Uint8Array(hex.length / 2);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

function base64ToBytes(encoded: string): Uint8Array {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function loadRecipes(): Promise<void> {
  try {
    const json = await (await fetch(browser.runtime.getURL("recipes/registry.json"))).text();
    const signature = await (await fetch(browser.runtime.getURL("recipes/registry.sig"))).text();
    const registry = await verifyRegistry(json, signature.trim(), RECIPE_PUBLIC_KEY);
    const stored = await browser.storage.local.get("recipeVersion");
    const current = typeof stored.recipeVersion === "number" ? stored.recipeVersion : 0;
    if (registry.version < current) {
      return;
    }
    recipeRegistry = registry;
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(json)));
    recipeHash = Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
    await browser.storage.local.set({ recipeVersion: registry.version });
  } catch {
    // Invalid bundles fail closed; autofill continues with heuristics only.
  }
}


browser.alarms.create("cipherkeep-sync", { periodInMinutes: 15 });
browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== "cipherkeep-sync") return;
  if (session === null || session.is_locked()) return;
  void browser.storage.local.get("syncConfig").then((stored) => {
    if (stored.syncConfig) void performSync().catch(() => undefined);
  });
});

async function load(): Promise<void> {
  const stored = await browser.storage.local.get("vaultBlob");
  if (typeof stored.vaultBlob !== "string") {
    return;
  }
  await ensureWasm();
  vaultBlob = stored.vaultBlob;
  session = WasmVaultSession.open(base64ToBytes(vaultBlob));
}

function codeOf(error: unknown): number | undefined {
  if (typeof error === "object" && error !== null && "code" in error) {
    const code = (error as { code?: unknown }).code;
    return typeof code === "number" ? code : undefined;
  }
  return undefined;
}

function messageFor(code: number | undefined): string {
  switch (code) {
    case 3:
      return "Vault could not be opened. Check your master password.";
    case 8:
      return "Too many attempts. Wait before trying again.";
    case 11:
      return "The password does not meet the minimum policy.";
    default:
      return "Vault operation failed.";
  }
}

function armAutoLock(): void {
  void browser.alarms.create(LOCK_ALARM, { delayInMinutes: 1 });
}

async function lockSession(): Promise<void> {
  session?.lock();
  shares = [];
  searchIndex?.clear();
  searchIndex = null;
  autofillTokens.clear();
  await browser.alarms.clear(LOCK_ALARM);
  try {
    await browser.runtime.sendMessage({ type: "locked" });
  } catch {
    // No popup is open.
  }
}

function requireSession(): WasmVaultSession {
  if (session === null || session.is_locked()) {
    throw Object.assign(new Error("vault is locked"), { code: 5 });
  }
  return session;
}

function rebuildSearch(items: ItemView[]): void {
  searchIndex?.clear();
  searchIndex = new WasmSearchIndex();
  for (const item of items) {
    searchIndex.insert(
      hexToBytes(item.id),
      item.title,
      item.username,
      item.url,
      [item.folder, ...item.tags].filter((value) => value.length > 0).join(" "),
      item.notes,
    );
  }
}

async function readFolderNames(active: WasmVaultSession): Promise<string[]> {
  await ensureItemStore();
  const records = await itemStore.list();
  const meta = records.find((record) => record.id === FOLDER_META_ID);
  if (!meta) {
    return [];
  }
  const plaintext = active.decrypt_item(hexToBytes(meta.id), meta.ciphertext);
  try {
    const json = JSON.parse(new TextDecoder().decode(plaintext)) as { folders?: unknown };
    return Array.isArray(json.folders)
      ? json.folders.filter((name): name is string => typeof name === "string" && name.length > 0)
      : [];
  } finally {
    plaintext.fill(0);
  }
}

async function writeFolderNames(active: WasmVaultSession, names: string[]): Promise<void> {
  await ensureItemStore();
  const payload = new TextEncoder().encode(
    JSON.stringify({ v: 1, type: "folder-list", folders: [...new Set(names)].sort() }),
  );
  const ciphertext = active.encrypt_item(hexToBytes(FOLDER_META_ID), payload);
  payload.fill(0);
  await itemStore.put({ id: FOLDER_META_ID, ciphertext, updatedAt: Date.now() });
}

async function listItems(): Promise<ItemView[]> {
  await ensureItemStore();
  const active = requireSession();
  const records = await itemStore.list();
  const items: ItemView[] = [];
  for (const record of records) {
    if (record.id === FOLDER_META_ID) {
      continue;
    }
    try {
      const payload = active.decrypt_item(hexToBytes(record.id), record.ciphertext);
      const json = JSON.parse(new TextDecoder().decode(payload)) as {
        title?: unknown;
        username?: unknown;
        password?: unknown;
        type?: unknown;
        totpUri?: unknown;
        url?: unknown;
        notes?: unknown;
        folder?: unknown;
        tags?: unknown;
        favorite?: unknown;
        autofillAllowed?: unknown;
      };
      items.push({
        id: record.id,
        title: typeof json.title === "string" ? json.title : "",
        username: typeof json.username === "string" ? json.username : "",
        password: typeof json.password === "string" ? json.password : "",
        totpUri: typeof json.totpUri === "string" ? json.totpUri : "",
        url: typeof json.url === "string" ? json.url : "",
        notes: typeof json.notes === "string" ? json.notes : "",
        folder: typeof json.folder === "string" ? json.folder : "",
        tags: Array.isArray(json.tags)
          ? json.tags.filter((tag): tag is string => typeof tag === "string")
          : [],
        favorite: json.favorite === true,
        autofillAllowed: json.autofillAllowed === true,
        updatedAt: record.updatedAt,
      });
    } catch {
      // A record from a different vault is skipped rather than exposed.
    }
  }
  rebuildSearch(items);
  return items;
}

interface BackoffState {
  failedAttempts: number;
  nextAttemptAt: number;
}

async function readBackoff(): Promise<BackoffState> {
  const stored = await browser.storage.local.get([UNLOCK_FAILED, UNLOCK_NEXT]);
  return {
    failedAttempts: typeof stored[UNLOCK_FAILED] === "number" ? stored[UNLOCK_FAILED] : 0,
    nextAttemptAt: typeof stored[UNLOCK_NEXT] === "number" ? stored[UNLOCK_NEXT] : 0,
  };
}

async function recordFailure(nowSeconds: number): Promise<BackoffState> {
  const current = await readBackoff();
  const failedAttempts = current.failedAttempts + 1;
  const delay = backoffSeconds(failedAttempts);
  const nextAttemptAt = delay === 0 ? 0 : nowSeconds + delay;
  await browser.storage.local.set({
    [UNLOCK_FAILED]: failedAttempts,
    [UNLOCK_NEXT]: nextAttemptAt,
  });
  return { failedAttempts, nextAttemptAt };
}

async function clearBackoff(): Promise<void> {
  await browser.storage.local.remove([UNLOCK_FAILED, UNLOCK_NEXT]);
}

async function status(): Promise<Response> {
  return {
    ok: true,
    kind: "status",
    hasVault: vaultBlob !== null,
    locked: session?.is_locked() ?? true,
    retryAfter: Math.max(
      session?.retry_after() ?? 0,
      remainingSeconds((await readBackoff()).nextAttemptAt, Math.floor(Date.now() / 1000)),
    ),
  };
}

async function performSync(): Promise<Response> {

        const active = requireSession();
        const stored = await browser.storage.local.get(["syncConfig", "syncActor", "syncCreated"]);
        const config: SyncConfig = { ...EMPTY_SYNC_CONFIG, ...(stored.syncConfig ?? {}) };
        const errors = validateSyncConfig(config);
        if (errors.length > 0) {
          return { ok: false, error: errors.join("; ") };
        }
        let actor = stored.syncActor as string | undefined;
        if (typeof actor !== "string" || actor.length !== 32) {
          const bytes = crypto.getRandomValues(new Uint8Array(16));
          actor = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
          await browser.storage.local.set({ syncActor: actor, syncCreated: Date.now() });
        }
        const created = typeof stored.syncCreated === "number" ? stored.syncCreated : Date.now();
        const actorBytes = new Uint8Array(actor.match(/.{2}/g)!.map((value) => Number.parseInt(value, 16)));
        await ensureItemStore();
        const records = await itemStore.list();
        const local: string[] = [];
        for (const record of records) {
          if (record.id === FOLDER_META_ID) continue;
          const exported = active.export_sync_record(
            hexToBytes(record.id),
            record.ciphertext,
            actorBytes,
            created,
            1,
          );
          local.push(new TextDecoder().decode(exported));
        }
        const vaultId = Array.from(active.vault_id(), (byte) => byte.toString(16).padStart(2, "0")).join("");
        const transport =
          config.transport === "github"
            ? new GitHubTransport(config.owner, config.repo, config.branch, config.token)
            : config.transport === "relay"
              ? new RelayTransport(config.endpoint, config.token)
              : new WebDavTransport(config.endpoint, config.username, config.token);
        const remote = await transport.pull(vaultId);
        let remoteRecords: string[] = [];
        if (remote !== null) {
          const parsed = JSON.parse(new TextDecoder().decode(remote.manifest)) as { records?: unknown };
          if (Array.isArray(parsed.records)) {
            remoteRecords = parsed.records.filter((value): value is string => typeof value === "string");
          }
        }
        const merged = JSON.parse(merge_sync_records_v2(local, remoteRecords)) as {
          records: string[];
          conflicts: unknown[];
        };
        const payload = new TextEncoder().encode(
          JSON.stringify({ v: 1, vault: vaultId, records: merged.records }),
        );
        await transport.push(vaultId, payload, remote?.etag);
        for (const raw of merged.records) {
          const wrapper = JSON.parse(raw) as { item?: string };
          if (typeof wrapper.item !== "string") continue;
          const legacy = active.apply_sync_record(new TextEncoder().encode(raw));
          const id = wrapper.item;
          const ciphertext = active.encrypt_item(hexToBytes(id), legacy);
          legacy.fill(0);
          await itemStore.put({ id, ciphertext, updatedAt: Date.now() });
        }
        await browser.storage.local.set({ syncConflicts: merged.conflicts });
        return { ok: true, kind: "sync", records: merged.records.length, conflicts: merged.conflicts.length };
}

async function handle(request: Request): Promise<Response> {
  try {
    await ensureWasm();
    switch (request.type) {
      case "status":
        return status();
      case "check-updates":
        return { ok: true, kind: "update", update: await checkForUpdates() };
      case "generate-password": {
        const generated = generate_password(
          request.length,
          request.lowercase,
          request.uppercase,
          request.digits,
          request.symbols,
          request.excludeAmbiguous,
        );
        return {
          ok: true,
          kind: "generated",
          value: generated.value,
          entropyBits: generated.entropy_bits,
          strength: generated.strength,
        };
      }
      case "generate-passphrase": {
        const generated = generate_passphrase(request.words, request.separator);
        return {
          ok: true,
          kind: "generated",
          value: generated.value,
          entropyBits: generated.entropy_bits,
          strength: generated.strength,
        };
      }
      case "create": {
        if (vaultBlob !== null) {
          return { ok: false, error: "A vault already exists." };
        }
        const password = new TextEncoder().encode(request.password);
        const created = WasmVaultSession.create(password);
        password.fill(0);
        vaultBlob = bytesToBase64(created.blob());
        await browser.storage.local.set({ vaultBlob });
        session = created;
        await clearBackoff();
        shares = created.recovery_shares();
        armAutoLock();
        return { ok: true, kind: "ok", shares };
      }
      case "unlock": {
        if (vaultBlob === null) {
          return { ok: false, error: "No vault exists." };
        }
        const now = Math.floor(Date.now() / 1000);
        const guard = await readBackoff();
        const retry = remainingSeconds(guard.nextAttemptAt, now);
        if (retry > 0) {
          return { ok: false, error: `Too many attempts. Wait ${retry} seconds.`, code: 8 };
        }
        session ??= WasmVaultSession.open(base64ToBytes(vaultBlob));
        const password = new TextEncoder().encode(request.password);
        try {
          session.unlock_again(password);
        } catch (error) {
          if (codeOf(error) === 3) {
            await recordFailure(now);
          }
          throw error;
        } finally {
          password.fill(0);
        }
        await clearBackoff();
        armAutoLock();
        return { ok: true, kind: "ok" };
      }
      case "lock":
        await lockSession();
        return { ok: true, kind: "ok" };
      case "activity":
        if (session !== null && !session.is_locked()) {
          armAutoLock();
        }
        return { ok: true, kind: "ok" };
      case "list-items":
        return { ok: true, kind: "items", items: await listItems() };
      case "save-item": {
        const active = requireSession();
        await ensureItemStore();
        const idBytes = crypto.getRandomValues(new Uint8Array(16));
        const payload = new TextEncoder().encode(
          JSON.stringify({
            v: 1,
            type: "login",
            title: request.title,
            username: request.username,
            password: request.password,
            url: request.url ?? "",
            notes: request.notes ?? "",
            folder: request.folder ?? "",
            tags: request.tags ?? [],
            favorite: request.favorite === true,
          }),
        );
        const ciphertext = active.encrypt_item(idBytes, payload);
        payload.fill(0);
        await itemStore.put({
          id: bytesToHex(idBytes),
          ciphertext,
          updatedAt: Date.now(),
        });
        return { ok: true, kind: "items", items: await listItems() };
      }
      case "export-backup": {
        const items = await listItems();
        const json = new TextEncoder().encode(JSON.stringify({ v: 1, items }));
        const bytes = export_portable(json, request.password);
        json.fill(0);
        return { ok: true, kind: "backup", data: bytesToBase64(bytes) };
      }
      case "update-item": {
        const active = requireSession();
        await ensureItemStore();
        const records = await itemStore.list();
        const record = records.find((entry) => entry.id === request.id);
        if (!record) {
          return { ok: false, error: "Item not found." };
        }
        const plaintext = active.decrypt_item(hexToBytes(record.id), record.ciphertext);
        const json = JSON.parse(new TextDecoder().decode(plaintext)) as Record<string, unknown>;
        plaintext.fill(0);
        Object.assign(json, {
          title: request.title,
          username: request.username,
          password: request.password,
          url: request.url,
          notes: request.notes,
          folder: request.folder,
          tags: request.tags,
          favorite: request.favorite,
          autofillAllowed: request.autofillAllowed,
        });
        const payload = new TextEncoder().encode(JSON.stringify(json));
        const ciphertext = active.encrypt_item(hexToBytes(record.id), payload);
        payload.fill(0);
        await itemStore.put({ id: record.id, ciphertext, updatedAt: Date.now() });
        return { ok: true, kind: "items", items: await listItems() };
      }
      case "sync-now":
        return await performSync();
      case "sync-vault-id": {
        const active = requireSession();
        const id = Array.from(active.vault_id(), (byte) => byte.toString(16).padStart(2, "0")).join("");
        return { ok: true, kind: "vault-id", vaultId: id };
      }
      case "recipe-status":
        return {
          ok: true,
          kind: "recipes",
          version: recipeRegistry?.version ?? 0,
          hash: recipeHash,
        };
      case "inspect-autofill": {
        const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
        if (!tab?.url) {
          return { ok: false, error: "No active page." };
        }
        const origin = hostOf(tab.url);
        if (origin === null) {
          return { ok: false, error: "Page URL is invalid." };
        }
        const locked = session === null || session.is_locked();
        const items = locked ? [] : await listItems();
        const matching = items.filter((item) => {
          const itemHost = hostOf(item.url);
          return itemHost !== null && itemHost.toLowerCase() === origin.toLowerCase();
        });
        return {
          ok: true,
          kind: "inspection",
          origin,
          locked,
          matching: matching.length,
          consented: matching.filter((item) => item.autofillAllowed).length,
        };
      }
      case "autofill-request": {
        const active = requireSession();
        const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
        if (!tab?.id || !tab.url) {
          return { ok: false, error: "No active page." };
        }
        const tabHost = hostOf(tab.url);
        if (tabHost === null) {
          return { ok: false, error: "Page URL is invalid." };
        }
        const items = await listItems();
        const item = items.find((candidate) => candidate.id === request.id);
        if (!item || item.url.length === 0 || !item.autofillAllowed) {
          return { ok: false, error: "Autofill is not enabled for this item." };
        }
        const itemHost = hostOf(item.url);
        if (itemHost === null) {
          return { ok: false, error: "Item URL is invalid." };
        }
        if (itemHost.toLowerCase() !== tabHost.toLowerCase()) {
          return { ok: false, error: "Item origin does not match this page." };
        }
        if (active.is_locked()) {
          return { ok: false, error: "Vault is locked." };
        }
        const recipe = recipeRegistry === null ? undefined : recipeForOrigin(recipeRegistry, tabHost);
        const token = crypto.randomUUID();
        autofillTokens.set(token, {
          username: item.username,
          password: item.password,
          ...(recipe === undefined ? {} : {
            usernameSelector: recipe.usernameSelector,
            passwordSelector: recipe.passwordSelector,
          }),
          expiresAt: Date.now() + 5_000,
        });
        return { ok: true, kind: "autofill-token", token };
      }
      case "consume-autofill": {
        requireSession();
        const entry = autofillTokens.get(request.token);
        autofillTokens.delete(request.token);
        if (!entry || entry.expiresAt < Date.now()) {
          return { ok: false, error: "Autofill token expired." };
        }
        return {
          ok: true,
          kind: "credentials",
          username: entry.username,
          password: entry.password,
          ...(entry.usernameSelector === undefined ? {} : {
            usernameSelector: entry.usernameSelector,
            passwordSelector: entry.passwordSelector,
          }),
        };
      }
      case "import-backup": {
        const active = requireSession();
        await ensureItemStore();
        const plaintext = import_portable(base64ToBytes(request.data), request.password);
        const json = JSON.parse(new TextDecoder().decode(plaintext)) as { items?: unknown };
        plaintext.fill(0);
        const items = Array.isArray(json.items) ? json.items : [];
        if (items.length > 5_000) {
          return { ok: false, error: "Backup item limit exceeded." };
        }
        let imported = 0;
        for (const raw of items) {
          if (typeof raw !== "object" || raw === null) {
            continue;
          }
          const item = raw as Record<string, unknown>;
          const text = (value: unknown, max: number): string | null =>
            typeof value === "string" && value.length <= max ? value : null;
          const title = text(item.title, 512);
          const type = item.type === "totp" ? "totp" : item.type === "login" ? "login" : null;
          const username = text(item.username, 4_096);
          const password = text(item.password, 65_536);
          const url = text(item.url, 2_048);
          const notes = text(item.notes, 65_536);
          const folder = text(item.folder, 64);
          const totpUri = text(item.totpUri, 4_096);
          const tags = Array.isArray(item.tags)
            ? item.tags.filter((tag): tag is string => typeof tag === "string" && tag.length <= 64).slice(0, 64)
            : [];
          if (!title || title.length === 0 || !type || username === null || password === null ||
              url === null || notes === null || folder === null || totpUri === null) {
            continue;
          }
          if (type === "totp") {
            try {
              totp_code(totpUri ?? "", Math.floor(Date.now() / 1000));
            } catch {
              continue;
            }
          }
          const idBytes = crypto.getRandomValues(new Uint8Array(16));
          const payload = new TextEncoder().encode(
            JSON.stringify({
              v: 1, type, title, username, password, url, notes, folder, tags,
              favorite: item.favorite === true,
              autofillAllowed: item.autofillAllowed === true,
              totpUri: type === "totp" ? totpUri : "",
            }),
          );
          const ciphertext = active.encrypt_item(idBytes, payload);
          payload.fill(0);
          await itemStore.put({ id: bytesToHex(idBytes), ciphertext, updatedAt: Date.now() });
          imported += 1;
        }
        return { ok: true, kind: "ok", imported };
      }
      case "create-folder": {
        const active = requireSession();
        const names = await readFolderNames(active);
        names.push(request.name.trim());
        await writeFolderNames(active, names);
        return { ok: true, kind: "items", items: await listItems() };
      }
      case "rename-folder":
      case "delete-folder": {
        const active = requireSession();
        await ensureItemStore();
        const oldName = request.type === "rename-folder" ? request.oldName : request.name;
        const newName = request.type === "rename-folder" ? request.newName : "";
        const names = await readFolderNames(active);
        const updatedNames = names.filter((name) => name !== oldName);
        if (newName.length > 0) {
          updatedNames.push(newName);
        }
        const records = await itemStore.list();
        const changed: typeof records = [];
        try {
          for (const record of records) {
            if (record.id === FOLDER_META_ID) {
              continue;
            }
            const plaintext = active.decrypt_item(hexToBytes(record.id), record.ciphertext);
            const json = JSON.parse(new TextDecoder().decode(plaintext)) as {
              folder?: unknown;
              [key: string]: unknown;
            };
            plaintext.fill(0);
            if (json.folder !== oldName) {
              continue;
            }
            json.folder = newName;
            const payload = new TextEncoder().encode(JSON.stringify(json));
            const ciphertext = active.encrypt_item(hexToBytes(record.id), payload);
            payload.fill(0);
            await itemStore.put({
              id: record.id,
              ciphertext,
              updatedAt: Date.now(),
            });
            changed.push(record);
          }
          await writeFolderNames(active, updatedNames);
        } catch (error) {
          for (const original of changed) {
            await itemStore.put(original);
          }
          throw error;
        }
        return { ok: true, kind: "items", items: await listItems() };
      }
      case "save-totp-item": {
        const active = requireSession();
        await ensureItemStore();
        // Reject an unusable URI before it reaches encrypted storage.
        totp_code(request.uri, Math.floor(Date.now() / 1000));
        const idBytes = crypto.getRandomValues(new Uint8Array(16));
        const payload = new TextEncoder().encode(
          JSON.stringify({
            v: 1,
            type: "totp",
            title: request.title,
            username: request.account,
            totpUri: request.uri,
          }),
        );
        const ciphertext = active.encrypt_item(idBytes, payload);
        payload.fill(0);
        await itemStore.put({
          id: bytesToHex(idBytes),
          ciphertext,
          updatedAt: Date.now(),
        });
        return { ok: true, kind: "items", items: await listItems() };
      }
      case "search": {
        if (searchIndex === null) {
          await listItems();
        }
        const hits = (searchIndex?.search(request.query, 50) ?? []) as SearchHit[];
        return {
          ok: true,
          kind: "search",
          hits: hits.filter(
            (hit) =>
              typeof hit.id === "string" &&
              typeof hit.snippet === "string" &&
              typeof hit.score === "number",
          ),
        };
      }
      case "totp-code": {
        const generated = totp_code(request.uri, Math.floor(Date.now() / 1000));
        return {
          ok: true,
          kind: "totp",
          code: generated.code,
          secondsRemaining: generated.seconds_remaining,
        };
      }
      case "delete-item":
        requireSession();
        await ensureItemStore();
        await itemStore.delete(request.id);
        return { ok: true, kind: "items", items: await listItems() };
      case "recover": {
        if (vaultBlob === null) {
          return { ok: false, error: "No vault exists." };
        }
        const password = new TextEncoder().encode(request.newPassword);
        const recovered = WasmVaultSession.recover(
          base64ToBytes(vaultBlob),
          request.first.trim(),
          request.second.trim(),
          password,
        );
        password.fill(0);
        vaultBlob = bytesToBase64(recovered.blob());
        await browser.storage.local.set({ vaultBlob });
        session = recovered;
        await clearBackoff();
        shares = recovered.recovery_shares();
        armAutoLock();
        return { ok: true, kind: "ok", shares };
      }
    }
  } catch (error) {
    const code = codeOf(error);
    return code === undefined
      ? { ok: false, error: messageFor(undefined) }
      : { ok: false, error: messageFor(code), code };
  }
}

void Promise.all([load(), loadRecipes()]).catch(() => {
  // Status reports locked; no secret is logged from this path.
});

browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === LOCK_ALARM) {
    void lockSession();
  }
});

browser.runtime.onMessage.addListener((message: unknown, sender) => {
  if (sender.id !== browser.runtime.id) {
    return Promise.resolve<Response>({ ok: false, error: "unauthorized" });
  }
  if (!isRequest(message)) {
    return Promise.resolve<Response>({ ok: false, error: "bad request" });
  }
  return handle(message);
});
