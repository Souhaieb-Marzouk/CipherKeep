import type { Response } from "../shared/protocol";
import { EMPTY_SYNC_CONFIG, configForStorage, validateSyncConfig, type SyncConfig } from "../shared/sync-config";

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
