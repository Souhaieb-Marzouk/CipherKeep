import type { AliasView, Response } from "../shared/protocol";
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
element<HTMLButtonElement>("exportBundle").addEventListener("click", async () => {
  const status = element<HTMLPreElement>("syncStatus");
  const reply: Response = await browser.runtime.sendMessage({ type: "export-sync-bundle" });
  if (!reply.ok || reply.kind !== "bundle") {
    status.textContent = reply.ok ? "Unexpected bundle response." : reply.error;
    return;
  }
  const url = URL.createObjectURL(new Blob([reply.json], { type: "application/json" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "cipherkeep-sync-bundle.json";
  anchor.click();
  URL.revokeObjectURL(url);
  status.textContent = "Sync bundle downloaded.";
});

element<HTMLInputElement>("importBundle").addEventListener("change", async () => {
  const status = element<HTMLPreElement>("syncStatus");
  const file = element<HTMLInputElement>("importBundle").files?.[0];
  if (file === undefined) return;
  const json = await file.text();
  const reply: Response = await browser.runtime.sendMessage({ type: "import-sync-bundle", json });
  if (!reply.ok) {
    status.textContent = reply.error;
    return;
  }
  if (reply.kind === "sync") {
    status.textContent = `Imported bundle: ${reply.records} record(s), conflicts ${reply.conflicts}.`;
    await showConflicts();
  }
});


function showAliases(aliases: AliasView[]): void {
  const list = element<HTMLDivElement>("aliasList");
  list.replaceChildren();
  for (const alias of aliases) {
    const copy = document.createElement("button");
    copy.className = "secondary";
    copy.textContent = `Copy ${alias.label} (${alias.address})`;
    copy.addEventListener("click", async () => {
      await navigator.clipboard.writeText(alias.address);
      element<HTMLPreElement>("aliasStatus").textContent = "Alias copied.";
    });
    const disable = document.createElement("button");
    disable.className = "secondary";
    disable.textContent = `Disable ${alias.label}`;
    disable.disabled = !alias.enabled;
    disable.addEventListener("click", async () => {
      const reply: Response = await browser.runtime.sendMessage({ type: "disable-alias", address: alias.address });
      if (reply.ok && reply.kind === "aliases") {
        showAliases(reply.aliases);
        element<HTMLPreElement>("aliasStatus").textContent = "Alias disabled.";
      } else if (!reply.ok) {
        element<HTMLPreElement>("aliasStatus").textContent = reply.error;
      }
    });
    const remove = document.createElement("button");
    remove.className = "secondary";
    remove.textContent = `Delete ${alias.label}`;
    remove.addEventListener("click", async () => {
      const reply: Response = await browser.runtime.sendMessage({
        type: "delete-alias",
        address: alias.address,
        id: alias.id,
      });
      if (reply.ok && reply.kind === "aliases") {
        showAliases(reply.aliases);
        element<HTMLPreElement>("aliasStatus").textContent = "Alias deleted.";
      } else if (!reply.ok) {
        element<HTMLPreElement>("aliasStatus").textContent = reply.error;
      }
    });
    list.append(copy, disable, remove);
  }
  if (aliases.length === 0) {
    list.textContent = "No aliases stored in this vault.";
  }
}

async function loadAliases(): Promise<void> {
  const reply: Response = await browser.runtime.sendMessage({ type: "list-aliases" });
  if (reply.ok && reply.kind === "aliases") showAliases(reply.aliases);
}

async function loadAliasConfig(): Promise<void> {
  const stored = await browser.storage.local.get("aliasConfig");
  const config = stored.aliasConfig as { provider?: string; baseUrl?: string; token?: string } | undefined;
  if (config === undefined) {
    element<HTMLPreElement>("aliasStatus").textContent =
      "No provider configured. Alias creation needs a provider you control.";
    return;
  }
  element<HTMLSelectElement>("aliasProvider").value = config.provider ?? "cloudflare";
  element<HTMLInputElement>("aliasBaseUrl").value = config.baseUrl ?? "";
  element<HTMLInputElement>("aliasToken").placeholder = "saved token (blank keeps it)";
  await loadAliases();
}

element<HTMLButtonElement>("saveAliasConfig").addEventListener("click", async () => {
  const provider = element<HTMLSelectElement>("aliasProvider").value;
  const baseUrl = element<HTMLInputElement>("aliasBaseUrl").value.trim();
  const typed = element<HTMLInputElement>("aliasToken").value;
  const stored = await browser.storage.local.get("aliasConfig");
  const previous = stored.aliasConfig as { token?: string } | undefined;
  const token = typed.length > 0 ? typed : previous?.token ?? "";
  if (baseUrl.length === 0 || token.length < 20) {
    element<HTMLPreElement>("aliasStatus").textContent = "Provider URL and token are required.";
    return;
  }
  await browser.storage.local.set({ aliasConfig: { provider, baseUrl, token } });
  element<HTMLPreElement>("aliasStatus").textContent =
    "Provider saved. The token is stored in browser.storage.local, not an OS keychain.";
  element<HTMLInputElement>("aliasToken").value = "";
  element<HTMLInputElement>("aliasToken").placeholder = "saved token (blank keeps it)";
  await loadAliases();
});

element<HTMLButtonElement>("createAlias").addEventListener("click", async () => {
  const label = element<HTMLInputElement>("aliasLabel").value.trim();
  if (label.length === 0) {
    element<HTMLPreElement>("aliasStatus").textContent = "Enter a site name.";
    return;
  }
  const reply: Response = await browser.runtime.sendMessage({ type: "create-alias", label });
  if (reply.ok && reply.kind === "aliases") {
    showAliases(reply.aliases);
    element<HTMLPreElement>("aliasStatus").textContent = "Alias created and stored in the vault.";
    element<HTMLInputElement>("aliasLabel").value = "";
  } else if (!reply.ok) {
    element<HTMLPreElement>("aliasStatus").textContent = reply.error;
  }
});


function renderEmergency(reply: Response): void {
  const status = element<HTMLPreElement>("emergencyStatus");
  if (!reply.ok) { status.textContent = reply.error; return; }
  if (reply.kind !== "emergency") return;
  status.textContent = `Policy ${reply.status.state}: deadline ${new Date((reply.status.lastCheckIn + reply.status.inactivitySecs + reply.status.graceSecs) * 1000).toLocaleString()}`;
  if (reply.contactShares !== undefined) {
    element<HTMLPreElement>("emergencyShares").textContent =
      "Give each share to its contact offline. The cron only receives the third share.\n" +
      reply.contactShares.map((entry) => `${entry.id}: ${entry.share}`).join("\n");
  }
}

element<HTMLButtonElement>("armEmergency").addEventListener("click", async () => {
  const contacts = [element<HTMLInputElement>("emergencyContact1").value.trim(), element<HTMLInputElement>("emergencyContact2").value.trim()]
    .filter((value) => value.length > 0)
    .map((value, index) => ({ id: `contact-${index + 1}`, address: value }));
  const reply: Response = await browser.runtime.sendMessage({
    type: "arm-emergency",
    cronUrl: element<HTMLInputElement>("emergencyCronUrl").value.trim(),
    token: element<HTMLInputElement>("emergencyToken").value,
    contacts,
    inactivityDays: Number(element<HTMLInputElement>("emergencyDays").value),
    graceMinutes: Number(element<HTMLInputElement>("emergencyGrace").value),
  });
  renderEmergency(reply);
});

for (const [button, type] of [
  ["checkinEmergency", "checkin-emergency"],
  ["cancelEmergency", "cancel-emergency"],
] as const) {
  element<HTMLButtonElement>(button).addEventListener("click", async () => {
    const reply: Response = await browser.runtime.sendMessage({ type });
    renderEmergency(reply);
  });
}

async function showEmergencyStatus(): Promise<void> {
  const reply: Response = await browser.runtime.sendMessage({ type: "emergency-status" });
  renderEmergency(reply);
}

void showEmergencyStatus();
void loadAliasConfig();
void loadAliases();
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
