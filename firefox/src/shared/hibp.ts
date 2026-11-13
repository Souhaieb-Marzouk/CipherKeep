/** Opt-in Pwned Passwords range client. Only a five-character prefix leaves. */

const MAX_RESPONSE = 4 * 1024 * 1024;

export interface HibpResult {
  count: number;
  prefix: string;
}

export class HibpError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "HibpError";
  }
}

export async function hashPassword(password: string): Promise<{ prefix: string; suffix: string }> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(password));
  const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
  return { prefix: hex.slice(0, 5), suffix: hex.slice(5) };
}

export class HibpClient {
  private readonly cache = new Map<string, string>();
  private lastRequest = 0;

  clear(): void {
    this.cache.clear();
    this.lastRequest = 0;
  }

  async check(password: string): Promise<HibpResult> {
    const { prefix, suffix } = await hashPassword(password);
    let body = this.cache.get(prefix);
    if (body === undefined) {
      const wait = 1_500 - (Date.now() - this.lastRequest);
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      this.lastRequest = Date.now();
      const response = await fetch(`https://api.pwnedpasswords.com/range/${prefix}`, {
        redirect: "error",
        credentials: "omit",
        cache: "no-store",
        headers: { "Add-Padding": "true" },
      });
      if (!response.ok) throw new HibpError(`range request failed (${response.status})`);
      body = await response.text();
      if (body.length > MAX_RESPONSE) throw new HibpError("range response too large");
      this.cache.set(prefix, body);
    }
    let count = 0;
    for (const line of body.split(/\r?\n/)) {
      const colon = line.indexOf(":");
      if (colon !== 35) continue;
      if (line.slice(0, colon).toUpperCase() !== suffix) continue;
      count = Number.parseInt(line.slice(colon + 1), 10);
      if (!Number.isFinite(count)) count = 0;
      break;
    }
    return { count, prefix };
  }
}
