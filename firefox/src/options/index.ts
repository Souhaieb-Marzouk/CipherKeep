import type { Response } from "../shared/protocol";
import { EMPTY_SYNC_CONFIG, configForStorage, testSyncConnection, validateSyncConfig, type SyncConfig } from "../shared/sync-config";

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) {
    throw new Error(`missing element: ${id}`);
  }
  return found as T;
}

async function showSync(): Promise<void> {
  const stored = await browser.storage.local.get("syncConfig");
  const config: SyncConfig = { ...EMPTY_SYNC_CONFIG, ...(stored.syncConfig ?? {}) };
  element<HTMLSelectElement>("syncTransport").value = config.transport;
  element<HTMLInputElement>("syncEndpoint").value = config.endpoint;
  element<HTMLInputElement>("syncUsername").value = config.username;
  element<HTMLInputElement>("syncOwner").value = config.owner;
  element<HTMLInputElement>("syncRepo").value = config.repo;
  element<HTMLInputElement>("syncBranch").value = config.branch;
  element<HTMLInputElement>("syncToken").value = config.token;
}

element<HTMLButtonElement>("saveSync").addEventListener("click", async () => {
  const config = configForStorage({
    transport: element<HTMLSelectElement>("syncTransport").value as SyncConfig["transport"],
    endpoint: element<HTMLInputElement>("syncEndpoint").value,
    username: element<HTMLInputElement>("syncUsername").value,
    owner: element<HTMLInputElement>("syncOwner").value,
    repo: element<HTMLInputElement>("syncRepo").value,
    branch: element<HTMLInputElement>("syncBranch").value,
    token: element<HTMLInputElement>("syncToken").value,
  });
  const errors = validateSyncConfig(config);
  const status = element<HTMLPreElement>("syncStatus");
  if (errors.length > 0) {
    status.textContent = errors.join("\n");
    return;
  }
  await browser.storage.local.set({ syncConfig: config });
  status.textContent = "Saved. Sync execution is wired separately; no data was uploaded.";
});

element<HTMLButtonElement>("testSync").addEventListener("click", async () => {
  const status = element<HTMLPreElement>("syncStatus");
  const stored = await browser.storage.local.get("syncConfig");
  const config: SyncConfig = { ...EMPTY_SYNC_CONFIG, ...(stored.syncConfig ?? {}) };
  status.textContent = "Testing…";
  try {
    const vaultReply: Response = await browser.runtime.sendMessage({ type: "sync-vault-id" });
    if (!vaultReply.ok || vaultReply.kind !== "vault-id") {
      status.textContent = "Unlock a vault before testing sync.";
      return;
    }
    status.textContent = await testSyncConnection(config, vaultReply.vaultId);
  } catch (error) {
    status.textContent = `Test failed: ${error instanceof Error ? error.message : "unknown error"}`;
  }
});

element<HTMLButtonElement>("syncNow").addEventListener("click", async () => {
  const status = element<HTMLPreElement>("syncStatus");
  status.textContent = "Syncing…";
  const reply: Response = await browser.runtime.sendMessage({ type: "sync-now" });
  if (!reply.ok) {
    status.textContent = reply.error;
    return;
  }
  if (reply.kind !== "sync") {
    status.textContent = "Unexpected sync response.";
    return;
  }
  status.textContent = `Synced ${reply.records} record(s); unresolved conflicts: ${reply.conflicts}.`;
  await showConflicts();
});

async function showConflicts(): Promise<void> {
  const output = element<HTMLPreElement>("syncConflicts");
  const actions = element<HTMLDivElement>("syncConflictActions");
  actions.replaceChildren();
  const stored = await browser.storage.local.get("syncConflicts");
  const conflicts = Array.isArray(stored.syncConflicts) ? stored.syncConflicts : [];
  output.textContent =
    conflicts.length === 0
      ? ""
      : `Unresolved conflicts: ${conflicts.length}`;
  for (const value of conflicts) {
    const conflict = value as { item?: string; field?: number; kind?: string };
    if (typeof conflict.item !== "string" || typeof conflict.field !== "number") continue;
    const label = `${conflict.kind ?? "field"} · ${conflict.item.slice(0, 8)}… · field ${conflict.field}`;
    const keepLeft = document.createElement("button");
    keepLeft.textContent = `Keep left: ${label}`;
    keepLeft.className = "secondary";
    keepLeft.addEventListener("click", async () => {
      await browser.runtime.sendMessage({ type: "resolve-conflict", item: conflict.item, field: conflict.field, keepRight: false });
      await showConflicts();
    });
    const keepRight = document.createElement("button");
    keepRight.textContent = `Keep right: ${label}`;
    keepRight.addEventListener("click", async () => {
      await browser.runtime.sendMessage({ type: "resolve-conflict", item: conflict.item, field: conflict.field, keepRight: true });
      await showConflicts();
    });
    actions.append(keepLeft, keepRight);
  }
}
void showConflicts();
void showSync();

async function showRecipes(): Promise<void> {
  const output = element<HTMLPreElement>("recipeStatus");
  const reply: Response = await browser.runtime.sendMessage({ type: "recipe-status" });
  if (!reply.ok || reply.kind !== "recipes") {
    output.textContent = reply.ok ? "Unexpected recipe response." : reply.error;
    return;
  }
  output.textContent = `Version: ${reply.version}\nSHA-256: ${reply.hash || "not verified"}`;
}

void showRecipes();

element<HTMLButtonElement>("checkUpdates").addEventListener("click", async () => {
  const output = element<HTMLPreElement>("updateResult");
  output.textContent = "Checking GitHub Releases…";
  const reply: Response = await browser.runtime.sendMessage({ type: "check-updates" });
  if (!reply.ok) {
    output.textContent = reply.error;
    return;
  }
  if (reply.kind !== "update") {
    output.textContent = "Unexpected update response.";
    return;
  }
  const update = reply.update;
  switch (update.kind) {
    case "available":
      output.textContent =
        `Update available: ${update.currentVersion} → ${update.latestVersion}\n` +
        `Published: ${update.publishedAt}\nSize: ${update.sizeBytes} bytes\n` +
        `${update.notes}\n${update.releaseUrl}`;
      break;
    case "up-to-date":
      output.textContent = `Up to date (${update.currentVersion}).`;
      break;
    case "rate-limited":
      output.textContent = "GitHub rate limit reached. Try again later.";
      break;
    case "error":
      output.textContent = update.message;
      break;
  }
});
