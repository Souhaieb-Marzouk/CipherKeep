import type { Response } from "../shared/protocol";

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

async function loadItems(): Promise<void> {
  const reply = await send({ type: "list-items" });
  if (!reply.ok || reply.kind !== "items") {
    return;
  }
  const list = element<HTMLUListElement>("itemList");
  list.replaceChildren();
  for (const item of reply.items) {
    const row = document.createElement("li");
    row.textContent = `${item.title} — ${item.username}`;
    list.append(row);
  }
}

element<HTMLButtonElement>("saveItem").addEventListener("click", async () => {
  const title = element<HTMLInputElement>("itemTitle");
  const username = element<HTMLInputElement>("itemUsername");
  const password = element<HTMLInputElement>("itemPassword");
  if (title.value.trim().length === 0 || password.value.length === 0) {
    showMessage("Title and password are required.");
    return;
  }
  const reply = await send({
    type: "save-item",
    title: title.value.trim(),
    username: username.value.trim(),
    password: password.value,
  });
  title.value = "";
  username.value = "";
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
