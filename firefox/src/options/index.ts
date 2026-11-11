import type { Response } from "../shared/protocol";

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) {
    throw new Error(`missing element: ${id}`);
  }
  return found as T;
}

const originInput = element<HTMLInputElement>("origin");
const message = element<HTMLParagraphElement>("message");
const origins = element<HTMLUListElement>("origins");

function show(text: string, cls: "ok" | "error" | "" = ""): void {
  message.textContent = text;
  message.className = cls;
}

function originPattern(raw: string): string {
  const url = new URL(raw);
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error("only http(s) origins are supported");
  }
  return `${url.origin}/*`;
}

async function refresh(): Promise<void> {
  const granted = await browser.permissions.getAll();
  const list = granted.origins ?? [];
  origins.replaceChildren();
  if (list.length === 0) {
    const none = document.createElement("li");
    none.textContent = "None";
    origins.append(none);
    return;
  }
  for (const origin of list) {
    const item = document.createElement("li");
    item.textContent = origin;
    origins.append(item);
  }
}

element<HTMLButtonElement>("grant").addEventListener("click", async () => {
  try {
    const pattern = originPattern(originInput.value.trim());
    const granted = await browser.permissions.request({ origins: [pattern] });
    show(granted ? `Granted ${pattern}` : "Permission was not granted.", granted ? "ok" : "error");
    await refresh();
  } catch {
    show("Enter a valid http(s) origin.", "error");
  }
});

element<HTMLButtonElement>("remove").addEventListener("click", async () => {
  try {
    const pattern = originPattern(originInput.value.trim());
    await browser.permissions.remove({ origins: [pattern] });
    show(`Removed ${pattern}`, "ok");
    await refresh();
  } catch {
    show("Enter a valid http(s) origin.", "error");
  }
});

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

void refresh();
