import type { Response } from "../shared/protocol";

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) {
    throw new Error(`missing element: ${id}`);
  }
  return found as T;
}

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
