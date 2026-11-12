import type { ItemView, Response } from "../shared/protocol";

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) {
    throw new Error(`missing element: ${id}`);
  }
  return found as T;
}

const status = element<HTMLParagraphElement>("status");
const message = element<HTMLParagraphElement>("message");
const panels = [
  "setupPanel",
  "unlockPanel",
  "recoveryPanel",
  "sharesPanel",
  "vaultPanel",
].map((id) => element<HTMLElement>(id));

let pendingShares: string[] = [];
let shareIndex = 0;
let allItems: ItemView[] = [];
let searchTimer: number | null = null;
let selectedItem: ItemView | null = null;

function closeDetail(): void {
  selectedItem = null;
  element<HTMLElement>("detailPanel").hidden = true;
  element<HTMLInputElement>("detailPassword").type = "password";
}

function openDetail(item: ItemView): void {
  selectedItem = item;
  element<HTMLElement>("detailPanel").hidden = false;
  element<HTMLInputElement>("detailTitleInput").value = item.title;
  element<HTMLInputElement>("detailUsername").value = item.username;
  element<HTMLInputElement>("detailPassword").value = item.password;
  element<HTMLInputElement>("detailPassword").type = "password";
  element<HTMLInputElement>("detailUrl").value = item.url;
  element<HTMLTextAreaElement>("detailNotes").value = item.notes;
  element<HTMLInputElement>("detailFolder").value = item.folder;
  element<HTMLInputElement>("detailTags").value = item.tags.join(", ");
  element<HTMLInputElement>("detailFavorite").checked = item.favorite;
}

element<HTMLButtonElement>("closeDetail").addEventListener("click", closeDetail);

element<HTMLButtonElement>("revealDetail").addEventListener("click", () => {
  const password = element<HTMLInputElement>("detailPassword");
  password.type = password.type === "password" ? "text" : "password";
});

element<HTMLButtonElement>("copyDetail").addEventListener("click", async () => {
  const value = element<HTMLInputElement>("detailPassword").value;
  if (value.length === 0) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    showMessage("Password copied; clipboard clears in 30 seconds while this popup stays open.", true);
    window.setTimeout(() => {
      void navigator.clipboard.writeText("").catch(() => undefined);
    }, 30_000);
  } catch {
    showMessage("Clipboard is unavailable.");
  }
});

element<HTMLButtonElement>("saveDetail").addEventListener("click", async () => {
  if (selectedItem === null) {
    return;
  }
  const title = element<HTMLInputElement>("detailTitleInput").value.trim();
  if (title.length === 0) {
    showMessage("Title is required.");
    return;
  }
  const reply = await send({
    type: "update-item",
    id: selectedItem.id,
    title,
    username: element<HTMLInputElement>("detailUsername").value.trim(),
    password: element<HTMLInputElement>("detailPassword").value,
    url: element<HTMLInputElement>("detailUrl").value.trim(),
    notes: element<HTMLTextAreaElement>("detailNotes").value,
    folder: element<HTMLInputElement>("detailFolder").value.trim(),
    tags: element<HTMLInputElement>("detailTags").value.split(",").map((tag) => tag.trim()).filter((tag) => tag.length > 0),
    favorite: element<HTMLInputElement>("detailFavorite").checked,
  });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  await loadItems();
  closeDetail();
  showMessage("Item saved.", true);
});

element<HTMLButtonElement>("deleteDetail").addEventListener("click", async () => {
  if (selectedItem === null) {
    return;
  }
  if (!window.confirm("Delete this encrypted item? This cannot be undone without a backup.")) {
    return;
  }
  const reply = await send({ type: "delete-item", id: selectedItem.id });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  await loadItems();
  closeDetail();
  showMessage("Item deleted.", true);
});

function showPanel(id: string): void {
  for (const panel of panels) {
    panel.hidden = panel.id !== id;
  }
}

function showMessage(text: string, ok = false): void {
  message.textContent = text;
  message.classList.toggle("ok", ok);
}

async function send(payload: unknown): Promise<Response> {
  return browser.runtime.sendMessage(payload) as Promise<Response>;
}

function showShares(shares: string[]): void {
  pendingShares = shares;
  shareIndex = 0;
  showPanel("sharesPanel");
  renderShare();
}

function renderShare(): void {
  const share = pendingShares[shareIndex] ?? "";
  element<HTMLDivElement>("shareValue").textContent = share;
  element<HTMLParagraphElement>("sharesHint").textContent =
    `Share ${shareIndex + 1} of ${pendingShares.length}. Store it offline; never keep all shares together.`;
  const saved = element<HTMLInputElement>("saved");
  saved.checked = false;
  element<HTMLButtonElement>("nextShare").disabled = true;
  element<HTMLButtonElement>("nextShare").textContent =
    shareIndex + 1 >= pendingShares.length ? "Finish" : "Next share";
}

function clearShares(): void {
  pendingShares = [];
  shareIndex = 0;
  element<HTMLDivElement>("shareValue").textContent = "";
}

async function refresh(): Promise<void> {
  try {
    const reply = await send({ type: "status" });
    if (!reply.ok || reply.kind !== "status") {
      status.textContent = "Status unavailable";
      return;
    }
    if (!reply.hasVault) {
      status.textContent = "No vault yet";
      showPanel("setupPanel");
    } else if (reply.locked) {
      status.textContent =
        reply.retryAfter > 0
          ? `Locked · wait ${reply.retryAfter}s`
          : "Locked";
      showPanel("unlockPanel");
    } else {
      status.textContent = "Unlocked";
      status.classList.add("unlocked");
      showPanel("vaultPanel");
      await loadItems();
    }
    if (reply.locked) {
      status.classList.remove("unlocked");
      clearGenerated();
      clearTotp();
      closeDetail();
    }
  } catch {
    status.textContent = "Background unavailable";
  }
}

element<HTMLButtonElement>("create").addEventListener("click", async () => {
  const password = element<HTMLInputElement>("setupPassword").value;
  const confirmation = element<HTMLInputElement>("setupConfirm").value;
  if (password !== confirmation) {
    showMessage("Passwords do not match.");
    return;
  }
  const reply = await send({ type: "create", password });
  element<HTMLInputElement>("setupPassword").value = "";
  element<HTMLInputElement>("setupConfirm").value = "";
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  status.textContent = "Vault created";
  if (reply.kind === "ok" && reply.shares) {
    showShares(reply.shares);
  } else {
    await refresh();
  }
});

element<HTMLButtonElement>("unlock").addEventListener("click", async () => {
  const input = element<HTMLInputElement>("password");
  const password = input.value;
  input.value = "";
  if (password.length === 0) {
    showMessage("Enter your master password.");
    return;
  }
  const reply = await send({ type: "unlock", password });
  if (!reply.ok) {
    showMessage(reply.error);
    await refresh();
    return;
  }
  showMessage("");
  await refresh();
});

async function fillActiveTab(username: string, password: string): Promise<void> {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) {
    showMessage("No active page to fill.");
    return;
  }
  const result = await browser.scripting.executeScript({
    target: { tabId: tab.id },
    func: (user: string, pass: string) => {
      const roots: ParentNode[] = [document];
      for (let index = 0; index < roots.length && index < 8; index += 1) {
        const root = roots[index]!;
        for (const element of Array.from(root.querySelectorAll("*"))) {
          if (element.shadowRoot) {
            roots.push(element.shadowRoot);
          }
        }
      }
      const visible = (element: HTMLInputElement): boolean =>
        element.offsetParent !== null && !element.disabled && !element.readOnly;
      let passwordField: HTMLInputElement | null = null;
      let usernameField: HTMLInputElement | null = null;
      for (const root of roots) {
        for (const element of Array.from(root.querySelectorAll<HTMLInputElement>("input"))) {
          if (!visible(element)) {
            continue;
          }
          const type = element.type.toLowerCase();
          const id = `${element.id} ${element.name} ${element.autocomplete}`.toLowerCase();
          if (type === "password" && passwordField === null) {
            passwordField = element;
          } else if (
            usernameField === null &&
            (type === "email" ||
              type === "text" ||
              type === "tel" ||
              id.includes("user") ||
              id.includes("email") ||
              id.includes("login"))
          ) {
            usernameField = element;
          }
        }
      }
      const set = (element: HTMLInputElement | null, value: string): void => {
        if (element === null || value.length === 0) {
          return;
        }
        element.focus();
        element.value = value;
        element.dispatchEvent(new Event("input", { bubbles: true }));
        element.dispatchEvent(new Event("change", { bubbles: true }));
      };
      set(usernameField, user);
      set(passwordField, pass);
      return passwordField !== null;
    },
    args: [username, password],
  });
  const filled = result.some((entry) => entry.result === true);
  showMessage(filled ? "Filled username/password. Review before submitting." : "No fillable password field found.", filled);
}

function renderItems(items: ItemView[]): void {
  const list = element<HTMLUListElement>("itemList");
  list.replaceChildren();
  const mode = element<HTMLSelectElement>("sortItems").value;
  const sorted = [...items].sort((left, right) => {
    if (mode === "recent") {
      return right.updatedAt - left.updatedAt || left.title.localeCompare(right.title);
    }
    if (mode === "folder") {
      return left.folder.localeCompare(right.folder) || left.title.localeCompare(right.title);
    }
    return left.title.localeCompare(right.title);
  });
  for (const item of sorted) {
    const row = document.createElement("li");
    const type = item.totpUri.length > 0 ? "TOTP · " : "";
    row.textContent = `${type}${item.title} — ${item.username}`;
    row.tabIndex = 0;
    row.style.cursor = "pointer";
    row.addEventListener("click", () => openDetail(item));
    row.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        openDetail(item);
      }
    });
    if (item.url.length > 0 && item.password.length > 0) {
      const fill = document.createElement("button");
      fill.type = "button";
      fill.className = "link";
      fill.textContent = "Fill";
      fill.addEventListener("click", async (event) => {
        event.stopPropagation();
        const reply = await send({ type: "autofill-request", id: item.id });
        if (!reply.ok) {
          showMessage(reply.error);
          return;
        }
        if (reply.kind !== "credentials") {
          return;
        }
        await fillActiveTab(reply.username, reply.password);
      });
      row.append(" ", fill);
    }
    list.append(row);
  }
}

async function loadItems(): Promise<void> {
  const reply = await send({ type: "list-items" });
  if (!reply.ok || reply.kind !== "items") {
    return;
  }
  allItems = reply.items;
  element<HTMLInputElement>("searchItems").value = "";
  renderItems(allItems);
}

function favoriteFiltered(items: ItemView[]): ItemView[] {
  return element<HTMLInputElement>("favoriteFilter").checked
    ? items.filter((item) => item.favorite)
    : items;
}

async function runSearch(): Promise<void> {
  const query = element<HTMLInputElement>("searchItems").value.trim();
  if (query.length === 0) {
    renderItems(favoriteFiltered(allItems));
    return;
  }
  const reply = await send({ type: "search", query });
  if (!reply.ok || reply.kind !== "search") {
    return;
  }
  const ids = new Set(reply.hits.map((hit) => hit.id));
  renderItems(favoriteFiltered(allItems.filter((item) => ids.has(item.id))));
}

element<HTMLInputElement>("favoriteFilter").addEventListener("change", () => {
  void runSearch();
});

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

element<HTMLButtonElement>("exportBackup").addEventListener("click", async () => {
  const password = element<HTMLInputElement>("backupPassword").value;
  if (password.length < 12) {
    showMessage("Use an export password of at least 12 characters.");
    return;
  }
  const reply = await send({ type: "export-backup", password });
  element<HTMLInputElement>("backupPassword").value = "";
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  if (reply.kind !== "backup") {
    return;
  }
  const binary = atob(reply.data);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  const url = URL.createObjectURL(new Blob([bytes], { type: "application/octet-stream" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `cipherkeep-backup-${new Date().toISOString().slice(0, 10)}.ckexp`;
  anchor.click();
  URL.revokeObjectURL(url);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  const checksum = Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
  showMessage(`Encrypted backup downloaded. SHA-256: ${checksum}`, true);
});

element<HTMLButtonElement>("importBackup").addEventListener("click", () => {
  if (element<HTMLInputElement>("backupPassword").value.length < 12) {
    showMessage("Enter the backup password first.");
    return;
  }
  element<HTMLInputElement>("backupFile").click();
});

element<HTMLInputElement>("backupFile").addEventListener("change", async () => {
  const input = element<HTMLInputElement>("backupFile");
  const file = input.files?.[0];
  input.value = "";
  if (!file) {
    return;
  }
  const password = element<HTMLInputElement>("backupPassword").value;
  const bytes = new Uint8Array(await file.arrayBuffer());
  const reply = await send({
    type: "import-backup",
    password,
    data: bytesToBase64(bytes),
  });
  element<HTMLInputElement>("backupPassword").value = "";
  if (!reply.ok || reply.kind !== "ok") {
    showMessage(reply.ok ? "Unexpected restore response." : reply.error);
    return;
  }
  await loadItems();
  showMessage(`Restored ${reply.imported ?? 0} items.`, true);
});

element<HTMLButtonElement>("createFolder").addEventListener("click", async () => {
  const name = element<HTMLInputElement>("folderCreate").value.trim();
  if (name.length === 0) {
    showMessage("Enter a folder name.");
    return;
  }
  const reply = await send({ type: "create-folder", name });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  element<HTMLInputElement>("folderCreate").value = "";
  await loadItems();
  showMessage("Folder created.", true);
});

element<HTMLSelectElement>("sortItems").addEventListener("change", () => {
  void runSearch();
});

element<HTMLButtonElement>("renameFolder").addEventListener("click", async () => {
  const oldName = element<HTMLInputElement>("folderName").value.trim();
  const newName = element<HTMLInputElement>("folderNew").value.trim();
  if (oldName.length === 0 || newName.length === 0) {
    showMessage("Enter the current and new folder names.");
    return;
  }
  const reply = await send({ type: "rename-folder", oldName, newName });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  element<HTMLInputElement>("folderName").value = "";
  element<HTMLInputElement>("folderNew").value = "";
  await loadItems();
  showMessage("Folder renamed; items moved with it.", true);
});

element<HTMLButtonElement>("deleteFolder").addEventListener("click", async () => {
  const name = element<HTMLInputElement>("folderName").value.trim();
  if (name.length === 0) {
    showMessage("Enter the folder to delete.");
    return;
  }
  const reply = await send({ type: "delete-folder", name });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  element<HTMLInputElement>("folderName").value = "";
  await loadItems();
  showMessage("Folder deleted; its items moved to Unfiled.", true);
});

element<HTMLInputElement>("searchItems").addEventListener("input", () => {
  if (searchTimer !== null) {
    window.clearTimeout(searchTimer);
  }
  searchTimer = window.setTimeout(() => { void runSearch(); }, 100);
});

function clearGenerated(): void {
  element<HTMLDivElement>("generatedValue").textContent = "";
  element<HTMLParagraphElement>("generatedMeta").textContent = "";
  element<HTMLElement>("generatedBox").hidden = true;
}

function syncGeneratorMode(): void {
  const passphrase = element<HTMLSelectElement>("genMode").value === "passphrase";
  element<HTMLElement>("passwordOptions").hidden = passphrase;
  element<HTMLElement>("passphraseOptions").hidden = !passphrase;
  clearGenerated();
}

element<HTMLSelectElement>("genMode").addEventListener("change", syncGeneratorMode);

element<HTMLButtonElement>("generate").addEventListener("click", async () => {
  const mode = element<HTMLSelectElement>("genMode").value;
  const reply =
    mode === "passphrase"
      ? await send({
          type: "generate-passphrase",
          words: element<HTMLInputElement>("genWords").valueAsNumber,
          separator: element<HTMLInputElement>("genSeparator").value || "-",
        })
      : await send({
          type: "generate-password",
          length: element<HTMLInputElement>("genLength").valueAsNumber,
          lowercase: element<HTMLInputElement>("genLower").checked,
          uppercase: element<HTMLInputElement>("genUpper").checked,
          digits: element<HTMLInputElement>("genDigits").checked,
          symbols: element<HTMLInputElement>("genSymbols").checked,
          excludeAmbiguous: element<HTMLInputElement>("genAmbiguous").checked,
        });
  if (!reply.ok) {
    showMessage(reply.error);
    clearGenerated();
    return;
  }
  if (reply.kind !== "generated") {
    showMessage("Generator returned an unexpected response.");
    return;
  }
  element<HTMLDivElement>("generatedValue").textContent = reply.value;
  element<HTMLParagraphElement>("generatedMeta").textContent =
    `${Math.round(reply.entropyBits)} bits Â· ${reply.strength}`;
  element<HTMLElement>("generatedBox").hidden = false;
  showMessage("");
});

element<HTMLButtonElement>("copyGenerated").addEventListener("click", async () => {
  const value = element<HTMLDivElement>("generatedValue").textContent ?? "";
  if (value.length === 0) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    showMessage("Copied. Clipboard is cleared after 30 seconds while this popup stays open.", true);
    window.setTimeout(() => {
      void navigator.clipboard.writeText("").catch(() => undefined);
    }, 30_000);
  } catch {
    showMessage("Clipboard is unavailable; select the value manually.");
  }
});

element<HTMLButtonElement>("useGenerated").addEventListener("click", () => {
  const value = element<HTMLDivElement>("generatedValue").textContent ?? "";
  if (value.length === 0) {
    return;
  }
  element<HTMLInputElement>("itemPassword").value = value;
  showMessage("Inserted into the password field; it is not saved until you click Save item.", true);
});

let totpTimer: number | null = null;
let totpCurrentUri = "";

function clearTotp(): void {
  if (totpTimer !== null) {
    window.clearInterval(totpTimer);
    totpTimer = null;
  }
  totpCurrentUri = "";
  element<HTMLDivElement>("totpValue").textContent = "";
  element<HTMLParagraphElement>("totpMeta").textContent = "";
  element<HTMLElement>("totpBox").hidden = true;
}

async function refreshTotp(): Promise<void> {
  if (totpCurrentUri.length === 0) {
    return;
  }
  const reply = await send({ type: "totp-code", uri: totpCurrentUri });
  if (!reply.ok) {
    clearTotp();
    showMessage(reply.error);
    return;
  }
  if (reply.kind !== "totp") {
    return;
  }
  element<HTMLDivElement>("totpValue").textContent = reply.code;
  element<HTMLParagraphElement>("totpMeta").textContent =
    `Refreshes in ${reply.secondsRemaining}s`;
  element<HTMLElement>("totpBox").hidden = false;
}

element<HTMLButtonElement>("showTotp").addEventListener("click", async () => {
  const uri = element<HTMLInputElement>("totpUri").value.trim();
  if (!uri.startsWith("otpauth://totp/")) {
    showMessage("Enter a valid otpauth://totp/ URI.");
    return;
  }
  clearTotp();
  totpCurrentUri = uri;
  await refreshTotp();
  if (totpCurrentUri.length > 0) {
    totpTimer = window.setInterval(() => { void refreshTotp(); }, 1_000);
  }
});

element<HTMLButtonElement>("saveTotp").addEventListener("click", async () => {
  const title = element<HTMLInputElement>("totpTitle").value.trim();
  const account = element<HTMLInputElement>("totpAccount").value.trim();
  const uri = element<HTMLInputElement>("totpUri").value.trim();
  if (title.length === 0 || account.length === 0 || !uri.startsWith("otpauth://totp/")) {
    showMessage("Title, account and a valid otpauth URI are required.");
    return;
  }
  const reply = await send({ type: "save-totp-item", title, account, uri });
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  element<HTMLInputElement>("totpTitle").value = "";
  element<HTMLInputElement>("totpAccount").value = "";
  element<HTMLInputElement>("totpUri").value = "";
  if (reply.kind === "items") {
    await loadItems();
  }
  showMessage("TOTP item saved.", true);
});

element<HTMLButtonElement>("copyTotp").addEventListener("click", async () => {
  const value = element<HTMLDivElement>("totpValue").textContent ?? "";
  if (value.length === 0) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    showMessage("TOTP code copied. Clipboard is cleared after 30 seconds while this popup stays open.", true);
    window.setTimeout(() => {
      void navigator.clipboard.writeText("").catch(() => undefined);
    }, 30_000);
  } catch {
    showMessage("Clipboard is unavailable; select the code manually.");
  }
});

element<HTMLButtonElement>("saveItem").addEventListener("click", async () => {
  const title = element<HTMLInputElement>("itemTitle");
  const username = element<HTMLInputElement>("itemUsername");
  const url = element<HTMLInputElement>("itemUrl");
  const notes = element<HTMLTextAreaElement>("itemNotes");
  const folder = element<HTMLInputElement>("itemFolder");
  const tags = element<HTMLInputElement>("itemTags");
  const favorite = element<HTMLInputElement>("itemFavorite");
  const password = element<HTMLInputElement>("itemPassword");
  if (title.value.trim().length === 0 || password.value.length === 0) {
    showMessage("Title and password are required.");
    return;
  }
  const reply = await send({
    type: "save-item",
    title: title.value.trim(),
    username: username.value.trim(),
    url: url.value.trim(),
    notes: notes.value,
    folder: folder.value.trim(),
    tags: tags.value.split(",").map((tag) => tag.trim()).filter((tag) => tag.length > 0),
    favorite: favorite.checked,
    password: password.value,
  });
  title.value = "";
  username.value = "";
  url.value = "";
  notes.value = "";
  folder.value = "";
  tags.value = "";
  favorite.checked = false;
  password.value = "";
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  if (reply.kind === "items") {
    await loadItems();
  }
  showMessage("Item saved.", true);
});

element<HTMLButtonElement>("lock").addEventListener("click", lockVault);
element<HTMLButtonElement>("lockUnlocked").addEventListener("click", lockVault);

async function lockVault(): Promise<void> {
  await send({ type: "lock" });
  clearShares();
  clearGenerated();
  clearTotp();
  allItems = [];
  closeDetail();
  element<HTMLInputElement>("searchItems").value = "";
  renderItems([]);
  showMessage("Locked. The in-memory keyring was dropped.", true);
  await refresh();
}

element<HTMLButtonElement>("recoveryToggle").addEventListener("click", () => {
  showPanel("recoveryPanel");
  showMessage("");
});

element<HTMLButtonElement>("recoveryBack").addEventListener("click", () => {
  showPanel("unlockPanel");
  showMessage("");
});

element<HTMLButtonElement>("recover").addEventListener("click", async () => {
  const first = element<HTMLTextAreaElement>("share1").value;
  const second = element<HTMLTextAreaElement>("share2").value;
  const newPassword = element<HTMLInputElement>("newPassword").value;
  const reply = await send({ type: "recover", first, second, newPassword });
  element<HTMLInputElement>("newPassword").value = "";
  if (!reply.ok) {
    showMessage(reply.error);
    return;
  }
  if (reply.kind === "ok" && reply.shares) {
    showShares(reply.shares);
  } else {
    await refresh();
  }
});

element<HTMLInputElement>("saved").addEventListener("change", (event) => {
  element<HTMLButtonElement>("nextShare").disabled = !(
    event.target as HTMLInputElement
  ).checked;
});

element<HTMLButtonElement>("nextShare").addEventListener("click", async () => {
  if (shareIndex + 1 >= pendingShares.length) {
    clearShares();
    showMessage("Vault unlocked. Keep all recovery shares offline.", true);
    await refresh();
    return;
  }
  shareIndex += 1;
  renderShare();
});

for (const eventName of ["pointerdown", "keydown"] as const) {
  document.addEventListener(eventName, () => {
    void send({ type: "activity" });
  }, { passive: true });
}

browser.runtime.onMessage.addListener((message: unknown) => {
  if (typeof message === "object" && message !== null && (message as { type?: string }).type === "locked") {
    clearShares();
    showMessage("Locked after inactivity.", true);
    void refresh();
  }
});

void refresh();
