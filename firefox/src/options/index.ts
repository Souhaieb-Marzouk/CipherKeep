import type { AliasView, Response } from "../shared/protocol";
import { EMPTY_SYNC_CONFIG, configForStorage, testSyncConnection, validateSyncConfig, type SyncConfig } from "../shared/sync-config";

const hibpBreaches = new Map<string, number>();

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


async function loadTravel(): Promise<void> {
  const stored = await browser.storage.local.get("travelMode");
  element<HTMLDivElement>("travelBanner").hidden = stored.travelMode !== true;
}
element<HTMLButtonElement>("enableTravel").addEventListener("click", async () => {
  await browser.runtime.sendMessage({ type: "enter-travel" });
  await loadTravel();
});

element<HTMLButtonElement>("allowOriginButton").addEventListener("click", async () => {
  const origin = element<HTMLInputElement>("allowOrigin").value.trim();
  const reply: Response = await browser.runtime.sendMessage({ type: "allow-phishing-origin", origin });
  element<HTMLPreElement>("allowOriginStatus").textContent = reply.ok
    ? "Origin allowed after review. Suspicious-origin warnings will be skipped for this host."
    : reply.error;
  if (reply.ok) element<HTMLInputElement>("allowOrigin").value = "";
});
element<HTMLButtonElement>("clearPhishing").addEventListener("click", async () => {
  const reply: Response = await browser.runtime.sendMessage({ type: "clear-phishing-overrides" });
  element<HTMLPreElement>("allowOriginStatus").textContent = reply.ok
    ? "Phishing overrides cleared."
    : reply.error;
});

import { HibpClient } from "../shared/hibp";

async function loadHibp(): Promise<void> {
  const stored = await browser.storage.local.get("hibpEnabled");
  element<HTMLInputElement>("hibpEnabled").checked = stored.hibpEnabled === true;
}
element<HTMLInputElement>("hibpEnabled").addEventListener("change", async () => {
  const enabled = element<HTMLInputElement>("hibpEnabled").checked;
  await browser.storage.local.set({ hibpEnabled: enabled });
  element<HTMLPreElement>("hibpStatus").textContent = enabled
    ? "Breach checking enabled; only 5-character prefixes are sent."
    : "Breach checking disabled.";
});
element<HTMLButtonElement>("hibpScan").addEventListener("click", async () => {
  if (!element<HTMLInputElement>("hibpEnabled").checked) {
    element<HTMLPreElement>("hibpStatus").textContent = "Enable opt-in breach checking first.";
    return;
  }
  if (!window.confirm("Send one 5-character SHA-1 prefix per stored password to api.pwnedpasswords.com? No password or full hash is sent.")) {
    return;
  }
  const reply: Response = await browser.runtime.sendMessage({ type: "list-items" });
  if (!reply.ok || reply.kind !== "items") {
    element<HTMLPreElement>("hibpStatus").textContent = reply.ok ? "No items." : reply.error;
    return;
  }
  const client = new HibpClient();
  let pwned = 0;
  let checked = 0;
  for (const item of reply.items) {
    if (item.password.length === 0) continue;
    checked += 1;
    try {
      const count = (await client.check(item.password)).count;
      if (count > 0) {
        pwned += 1;
        hibpBreaches.set(item.id, count);
      }
    } catch {
      element<HTMLPreElement>("hibpStatus").textContent = "Breach provider unavailable; no result stored.";
      return;
    }
  }
  element<HTMLPreElement>("hibpStatus").textContent =
    `Checked ${checked} password(s); ${pwned} appear in known breaches.`;
});

element<HTMLButtonElement>("healthScan").addEventListener("click", async () => {
  const reply: Response = await browser.runtime.sendMessage({ type: "list-items" });
  const status = element<HTMLPreElement>("healthStatus");
  if (!reply.ok || reply.kind !== "items") {
    status.textContent = reply.ok ? "No items." : reply.error;
    return;
  }
  const payload = reply.items.map((item) => ({
    itemId: item.id,
    password: item.password,
    updatedAt: Math.floor(item.updatedAt / 1000),
    hasTotp: item.totpUri.length > 0,
    breached: hibpBreaches.get(item.id) ?? 0,
  }));
  try {
    const report = JSON.parse(health_report(JSON.stringify(payload), BigInt(Math.floor(Date.now() / 1000)), 90n * 86_400n)) as {
      weak: number; reused: number; old: number; breached: number; missingTotp: number;
    };
    status.textContent = `Weak ${report.weak} · Reused ${report.reused} · Old ${report.old} · Breached ${report.breached} · No TOTP ${report.missingTotp}. Analysis is session-only.`;
  } catch {
    status.textContent = "Health analysis failed.";
  }
});

interface FamilyMember { id: string; public: string; role: string; wrapped?: string }

async function loadFamily(): Promise<void> {
  const stored = await browser.storage.local.get(["familyIdentity", "familyMembers"]);
  const identity = stored.familyIdentity as { fingerprint?: string; secret?: string } | undefined;
  element<HTMLPreElement>("familyStatus").textContent = identity === undefined
    ? "No owner identity. Create one and verify fingerprints out of band."
    : `Owner fingerprint: ${identity.fingerprint ?? ""}`;
  const members = Array.isArray(stored.familyMembers) ? stored.familyMembers as FamilyMember[] : [];
  const list = element<HTMLDivElement>("familyMembers");
  list.replaceChildren();
  for (const member of members) {
    const roleButton = document.createElement("button");
    roleButton.className = "secondary";
    roleButton.textContent = member.role === "editor" ? `Make viewer ${member.id}` : `Make editor ${member.id}`;
    roleButton.addEventListener("click", async () => {
      const key = await browser.runtime.sendMessage({ type: "family-vault-key" });
      const vault = await browser.runtime.sendMessage({ type: "sync-vault-id" });
      if (!key.ok || key.kind !== "ok" || typeof key.value !== "string") return;
      if (!vault.ok || vault.kind !== "vault-id") return;
      const nextRole = member.role === "editor" ? "viewer" : "editor";
      member.role = nextRole;
      member.wrapped = family_wrap(vault.vaultId, key.value, member.id, member.public, nextRole);
      await browser.storage.local.set({ familyMembers: members });
      element<HTMLPreElement>("familyStatus").textContent = "Role changed and wrapped key updated.";
      await loadFamily();
    });
    const revoke = document.createElement("button");
    revoke.className = "secondary";
    revoke.textContent = `Revoke ${member.id}`;
    revoke.addEventListener("click", async () => {
      if (!window.confirm("Rotate the vault key and rewrap remaining members? A removed member keeps access to ciphertext they already had.")) return;
      const rotated = await browser.runtime.sendMessage({ type: "rotate-family-key" });
      if (!rotated.ok) { element<HTMLPreElement>("familyStatus").textContent = rotated.error; return; }
      const key = await browser.runtime.sendMessage({ type: "family-vault-key" });
      const vault = await browser.runtime.sendMessage({ type: "sync-vault-id" });
      if (!key.ok || key.kind !== "ok" || typeof key.value !== "string") return;
      if (!vault.ok || vault.kind !== "vault-id") return;
      const remaining = members.filter((entry) => entry.id !== member.id);
      for (const entry of remaining) {
        entry.wrapped = family_wrap(vault.vaultId, key.value, entry.id, entry.public, entry.role);
      }
      await browser.storage.local.set({ familyMembers: remaining });
      await loadFamily();
    });
    list.append(roleButton, revoke);
  }
}

element<HTMLButtonElement>("familyIdentity").addEventListener("click", async () => {
  const identity = JSON.parse(family_identity("owner", "owner")) as { fingerprint: string; secret: string; public: string };
  await browser.storage.local.set({ familyIdentity: identity });
  element<HTMLPreElement>("familyStatus").textContent = `Owner fingerprint: ${identity.fingerprint}`;
});

element<HTMLButtonElement>("familyInvite").addEventListener("click", async () => {
  const stored = await browser.storage.local.get(["familyMembers"]);
  const key = await browser.runtime.sendMessage({ type: "family-vault-key" });
  if (!key.ok || key.kind !== "ok" || typeof key.value !== "string") return;
  const id = element<HTMLInputElement>("familyMemberId").value.trim();
  const publicKey = element<HTMLInputElement>("familyPublic").value.trim().toLowerCase();
  const role = element<HTMLSelectElement>("familyRole").value;
  if (id.length === 0 || !/^[0-9a-f]{64}$/.test(publicKey)) {
    element<HTMLPreElement>("familyStatus").textContent = "Member id and 64-hex public key are required.";
    return;
  }
  const vault = await browser.runtime.sendMessage({ type: "sync-vault-id" });
  if (!vault.ok || vault.kind !== "vault-id") return;
  const wrapped = family_wrap(vault.vaultId, key.value, id, publicKey, role);
  const members = Array.isArray(stored.familyMembers) ? stored.familyMembers as FamilyMember[] : [];
  members.push({ id, public: publicKey, role, wrapped });
  await browser.storage.local.set({ familyMembers: members });
  element<HTMLPreElement>("familyStatus").textContent = "Member invited; verify the fingerprint out of band.";
  await loadFamily();
});

element<HTMLButtonElement>("familyRotate").addEventListener("click", async () => {
  if (!window.confirm("Rotate the vault key now? Existing wrapped member keys become stale.")) return;
  const reply = await browser.runtime.sendMessage({ type: "rotate-family-key" });
  element<HTMLPreElement>("familyStatus").textContent = reply.ok ? "Vault key rotated." : reply.error;
});


element<HTMLButtonElement>("joinFamily").addEventListener("click", async () => {
  try {
    const unwrapped = JSON.parse(family_unwrap(
      element<HTMLInputElement>("joinVaultId").value.trim(),
      element<HTMLInputElement>("joinMemberId").value.trim(),
      element<HTMLInputElement>("joinPublic").value.trim(),
      element<HTMLInputElement>("joinNonce").value.trim(),
      element<HTMLInputElement>("joinCiphertext").value.trim(),
      "editor",
      element<HTMLInputElement>("joinSecret").value.trim(),
    )) as { vaultKey?: string };
    if (typeof unwrapped.vaultKey !== "string") throw new Error("no key");
    const reply: Response = await browser.runtime.sendMessage({
      type: "join-family",
      password: element<HTMLInputElement>("joinPassword").value,
      vaultId: element<HTMLInputElement>("joinVaultId").value.trim(),
      vaultKey: unwrapped.vaultKey,
    });
    element<HTMLPreElement>("familyStatus").textContent = reply.ok
      ? "Joined family vault. Configure sync to pull items."
      : reply.error;
  } catch {
    element<HTMLPreElement>("familyStatus").textContent = "Join failed; check the envelope and secret.";
  }
});

element<HTMLButtonElement>("webauthnEnroll").addEventListener("click", async () => {
  const status = element<HTMLPreElement>("webauthnStatus");
  if (typeof PublicKeyCredential === "undefined") {
    status.textContent = "This browser does not expose WebAuthn. Password unlock remains available.";
    return;
  }
  try {
    const available = await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    if (!available) {
      status.textContent = "No platform authenticator is available. Connect a security key or use the password.";
      return;
    }
    const salt = crypto.getRandomValues(new Uint8Array(32));
    const userId = crypto.getRandomValues(new Uint8Array(16));
    const credential = (await navigator.credentials.create({
      publicKey: {
        challenge: crypto.getRandomValues(new Uint8Array(32)),
        rp: { name: "CipherKeep" },
        user: { id: userId, name: "owner", displayName: "CipherKeep owner" },
        pubKeyCredParams: [{ type: "public-key", alg: -7 }],
        authenticatorSelection: { userVerification: "required" },
        extensions: { prf: { eval: { first: salt } } } as AuthenticationExtensionsClientInputs,
      },
    })) as PublicKeyCredential | null;
    if (credential === null) throw new Error("cancelled");
    const results = credential.getClientExtensionResults() as {
      prf?: { results?: { first?: ArrayBuffer } };
    };
    const prf = results.prf?.results?.first;
    if (prf === undefined) {
      status.textContent = "This authenticator does not return a PRF result; enrollment is unsupported.";
      return;
    }
    const reply: Response = await browser.runtime.sendMessage({
      type: "webauthn-enroll",
      prf: btoa(String.fromCharCode(...new Uint8Array(prf))),
      credentialId: btoa(String.fromCharCode(...new Uint8Array(credential.rawId))),
      salt: btoa(String.fromCharCode(...salt)),
    });
    status.textContent = reply.ok
      ? "Hardware wrapping enrolled. Password and recovery shares remain valid fallbacks."
      : reply.error;
  } catch {
    status.textContent = "Enrollment failed or was cancelled. Password unlock remains available.";
  }
});

element<HTMLButtonElement>("webauthnUnlock").addEventListener("click", async () => {
  const status = element<HTMLPreElement>("webauthnStatus");
  const stored = await browser.storage.local.get("webauthn");
  const record = stored.webauthn as { credentialId?: string; salt?: string } | undefined;
  if (record?.credentialId === undefined || record.salt === undefined) {
    status.textContent = "No hardware wrapping is enrolled.";
    return;
  }
  try {
    const credentialId = Uint8Array.from(atob(record.credentialId), (character) => character.charCodeAt(0));
    const salt = Uint8Array.from(atob(record.salt), (character) => character.charCodeAt(0));
    const assertion = (await navigator.credentials.get({
      publicKey: {
        challenge: crypto.getRandomValues(new Uint8Array(32)),
        allowCredentials: [{ type: "public-key", id: credentialId }],
        userVerification: "required",
        extensions: { prf: { eval: { first: salt } } } as AuthenticationExtensionsClientInputs,
      },
    })) as PublicKeyCredential | null;
    const results = assertion?.getClientExtensionResults() as { prf?: { results?: { first?: ArrayBuffer } } } | undefined;
    const prf = results?.prf?.results?.first;
    if (prf === undefined) {
      status.textContent = "The authenticator did not return a PRF result.";
      return;
    }
    const reply: Response = await browser.runtime.sendMessage({
      type: "webauthn-unlock",
      prf: btoa(String.fromCharCode(...new Uint8Array(prf))),
    });
    status.textContent = reply.ok ? "Unlocked with the security key." : reply.error;
  } catch {
    status.textContent = "Unlock failed or was cancelled; password unlock remains available.";
  }
});
void loadFamily();
void loadHibp();
void loadTravel();
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
