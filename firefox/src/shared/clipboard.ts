/** Copy a secret and clear the clipboard after the timeout when possible. */
export async function copySecret(text: string, timeoutMs = 30_000): Promise<void> {
  await navigator.clipboard.writeText(text);
  setTimeout(() => {
    void navigator.clipboard.writeText("").catch(() => undefined);
  }, timeoutMs);
}
