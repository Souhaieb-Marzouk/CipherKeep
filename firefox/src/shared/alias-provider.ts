/** Provider-independent alias operations. */
export type AliasProviderKind = "cloudflare" | "simplelogin";

export interface AliasRecord {
  address: string;
  provider: AliasProviderKind;
  providerId: string;
  label: string;
  enabled: boolean;
}

export interface AliasProvider {
  kind(): AliasProviderKind;
  create(label: string): Promise<AliasRecord>;
  disable(address: string): Promise<void>;
  delete(address: string): Promise<void>;
  list(): Promise<AliasRecord[]>;
}

export class AliasProviderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AliasProviderError";
  }
}

const MAX_LABEL = 128;
const MAX_RESPONSE = 1024 * 1024;

function validateBaseUrl(value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new AliasProviderError("provider URL is invalid");
  }
  if (url.protocol !== "https:") {
    throw new AliasProviderError("provider URL must use HTTPS");
  }
  if (url.username.length > 0 || url.password.length > 0) {
    throw new AliasProviderError("provider URL must not contain credentials");
  }
  return url.origin;
}

function validateLabel(label: string): string {
  const trimmed = label.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_LABEL) {
    throw new AliasProviderError("label must be 1-128 characters");
  }
  return trimmed;
}

function slug(label: string): string {
  const value = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 24);
  return value.length === 0 ? "alias" : value;
}

async function boundedJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length > MAX_RESPONSE) throw new AliasProviderError("provider response too large");
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new AliasProviderError("provider returned invalid JSON");
  }
}

function record(value: unknown, provider: AliasProviderKind): AliasRecord {
  if (typeof value !== "object" || value === null) {
    throw new AliasProviderError("provider returned an invalid alias");
  }
  const item = value as Record<string, unknown>;
  const address =
    typeof item.address === "string"
      ? item.address
      : typeof item.email === "string"
        ? item.email
        : "";
  const providerId =
    typeof item.providerId === "string"
      ? item.providerId
      : typeof item.id === "string"
        ? item.id
        : "";
  const label = typeof item.label === "string" ? item.label : typeof item.note === "string" ? item.note : "";
  const enabled = item.enabled !== false;
  if (address.length === 0 || providerId.length === 0 || label.length === 0) {
    throw new AliasProviderError("provider returned an incomplete alias");
  }
  return { address, provider, providerId, label, enabled };
}

/** Cloudflare Email Worker fixture adapter. */
export class CloudflareAliasProvider implements AliasProvider {
  private readonly base: string;
  private readonly token: string;

  constructor(baseUrl: string, token: string) {
    this.base = validateBaseUrl(baseUrl);
    if (token.length < 32) throw new AliasProviderError("provider token is too short");
    this.token = token;
  }

  kind(): AliasProviderKind {
    return "cloudflare";
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers: {
        authorization: `Bearer ${this.token}`,
        "content-type": "application/json",
        ...(init.headers ?? {}),
      },
      redirect: "error",
      credentials: "omit",
      cache: "no-store",
    });
    if (!response.ok) throw new AliasProviderError(`provider request failed (${response.status})`);
    return response;
  }

  async create(label: string): Promise<AliasRecord> {
    const response = await this.request("/aliases", {
      method: "POST",
      body: JSON.stringify({ label: validateLabel(label) }),
    });
    return record(await boundedJson(response), this.kind());
  }

  async disable(address: string): Promise<void> {
    await this.request(`/aliases/${encodeURIComponent(address)}/disable`, { method: "POST" });
  }

  async delete(address: string): Promise<void> {
    await this.request(`/aliases/${encodeURIComponent(address)}/delete`, { method: "POST" });
  }

  async list(): Promise<AliasRecord[]> {
    const response = await this.request("/aliases");
    const value = await boundedJson(response);
    if (!Array.isArray(value)) throw new AliasProviderError("provider returned an invalid list");
    return value.map((item) => record(item, this.kind()));
  }
}

/** Self-hosted SimpleLogin adapter. */
export class SimpleLoginAliasProvider implements AliasProvider {
  private readonly base: string;
  private readonly token: string;

  constructor(baseUrl: string, token: string) {
    this.base = validateBaseUrl(baseUrl);
    if (token.length < 20) throw new AliasProviderError("provider token is too short");
    this.token = token;
  }

  kind(): AliasProviderKind {
    return "simplelogin";
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await fetch(`${this.base}${path}`, {
      ...init,
      headers: {
        authentication: this.token,
        "content-type": "application/json",
        ...(init.headers ?? {}),
      },
      redirect: "error",
      credentials: "omit",
      cache: "no-store",
    });
    if (!response.ok) throw new AliasProviderError(`provider request failed (${response.status})`);
    return response;
  }

  async create(label: string): Promise<AliasRecord> {
    const response = await this.request("/api/alias/create", {
      method: "POST",
      body: JSON.stringify({ alias_prefix: slug(validateLabel(label)), note: label.trim() }),
    });
    const value = (await boundedJson(response)) as { alias?: unknown };
    return record(value.alias, this.kind());
  }

  private async findId(address: string): Promise<string> {
    const aliases = await this.list();
    const found = aliases.find((alias) => alias.address === address);
    if (found === undefined) throw new AliasProviderError("alias not found");
    return found.providerId;
  }

  async disable(address: string): Promise<void> {
    const aliases = await this.list();
    const found = aliases.find((alias) => alias.address === address);
    if (found === undefined) throw new AliasProviderError("alias not found");
    if (!found.enabled) return;
    await this.request(`/api/alias/toggle/${encodeURIComponent(found.providerId)}`, { method: "POST" });
  }

  async delete(address: string): Promise<void> {
    const id = await this.findId(address);
    await this.request(`/api/alias/${encodeURIComponent(id)}`, { method: "DELETE" });
  }

  async list(): Promise<AliasRecord[]> {
    const response = await this.request("/api/aliases");
    const value = (await boundedJson(response)) as { aliases?: unknown };
    if (!Array.isArray(value.aliases)) throw new AliasProviderError("provider returned an invalid list");
    return value.aliases.map((item) => record(item, this.kind()));
  }
}
