/** Opaque sync transports. They receive only sealed manifest bytes. */

export interface OpaqueSnapshot {
  manifest: Uint8Array;
  etag?: string;
}

export interface SyncTransport {
  readonly id: string;
  pull(vaultId: string, sinceEtag?: string): Promise<OpaqueSnapshot | null>;
  push(vaultId: string, manifest: Uint8Array, etag?: string): Promise<string | undefined>;
}

const MAX_BYTES = 64 * 1024 * 1024;

function assertVault(vaultId: string): void {
  if (!/^[a-f0-9]{32}$/.test(vaultId)) {
    throw new Error("invalid vault id");
  }
}

function assertSize(bytes: Uint8Array): void {
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_BYTES) {
    throw new Error("invalid manifest size");
  }
}

async function readBounded(response: Response): Promise<Uint8Array> {
  const length = Number(response.headers.get("content-length") ?? "0");
  if (length > MAX_BYTES) {
    throw new Error("remote manifest too large");
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  assertSize(bytes);
  return bytes;
}

export class WebDavTransport implements SyncTransport {
  readonly id = "webdav";
  constructor(
    private readonly baseUrl: string,
    private readonly username: string,
    private readonly password: string,
    private readonly fetcher: typeof fetch = fetch,
  ) {
    if (!baseUrl.startsWith("https://")) {
      throw new Error("WebDAV requires HTTPS");
    }
  }

  private headers(extra: Record<string, string> = {}): Record<string, string> {
    return {
      authorization: `Basic ${btoa(`${this.username}:${this.password}`)}`,
      ...extra,
    };
  }

  async pull(vaultId: string, sinceEtag?: string): Promise<OpaqueSnapshot | null> {
    assertVault(vaultId);
    const response = await this.fetcher(`${this.baseUrl}/${vaultId}/manifest.json`, {
      headers: this.headers(sinceEtag === undefined ? {} : { "if-none-match": sinceEtag }),
    });
    if (response.status === 304 || response.status === 404) return null;
    if (!response.ok) throw new Error(`WebDAV pull failed: ${response.status}`);
    const etag = response.headers.get("etag");
    return { manifest: await readBounded(response), ...(etag === null ? {} : { etag }) };
  }

  async push(vaultId: string, manifest: Uint8Array, etag?: string): Promise<string | undefined> {
    assertVault(vaultId);
    assertSize(manifest);
    const response = await this.fetcher(`${this.baseUrl}/${vaultId}/manifest.json`, {
      method: "PUT",
      headers: this.headers({
        "content-type": "application/octet-stream",
        ...(etag === undefined ? {} : { "if-match": etag }),
      }),
      body: manifest as unknown as BodyInit,
    });
    if (!response.ok) throw new Error(`WebDAV push failed: ${response.status}`);
    return response.headers.get("etag") ?? undefined;
  }
}

export class GitHubTransport implements SyncTransport {
  readonly id = "github";
  constructor(
    private readonly owner: string,
    private readonly repo: string,
    private readonly branch: string,
    private readonly token: string,
    private readonly fetcher: typeof fetch = fetch,
  ) {}

  private path(vaultId: string): string {
    return `https://api.github.com/repos/${this.owner}/${this.repo}/contents/sync/${vaultId}/manifest.json?ref=${encodeURIComponent(this.branch)}`;
  }

  private headers(): Record<string, string> {
    return {
      authorization: `Bearer ${this.token}`,
      accept: "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
    };
  }

  async pull(vaultId: string, _sinceEtag?: string): Promise<OpaqueSnapshot | null> {
    assertVault(vaultId);
    const response = await this.fetcher(this.path(vaultId), { headers: this.headers() });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`GitHub pull failed: ${response.status}`);
    const body = (await response.json()) as { content?: string; sha?: string };
    if (typeof body.content !== "string") throw new Error("GitHub content missing");
    const bytes = Uint8Array.from(atob(body.content.replace(/\n/g, "")), (c) => c.charCodeAt(0));
    assertSize(bytes);
    return { manifest: bytes, ...(typeof body.sha === "string" ? { etag: body.sha } : {}) };
  }

  async push(vaultId: string, manifest: Uint8Array, etag?: string): Promise<string | undefined> {
    assertVault(vaultId);
    assertSize(manifest);
    const current = await this.pull(vaultId);
    if (etag !== undefined && current?.etag !== etag) {
      throw new Error("GitHub remote changed");
    }
    let binary = "";
    for (const byte of manifest) binary += String.fromCharCode(byte);
    const response = await this.fetcher(this.path(vaultId).split("?")[0]!, {
      method: "PUT",
      headers: { ...this.headers(), "content-type": "application/json" },
      body: JSON.stringify({
        message: "cipherkeep sync",
        content: btoa(binary),
        branch: this.branch,
        ...(current?.etag === undefined ? {} : { sha: current.etag }),
      }),
    });
    if (!response.ok) throw new Error(`GitHub push failed: ${response.status}`);
    const body = (await response.json()) as { content?: { sha?: string } };
    return body.content?.sha;
  }
}

export class RelayTransport implements SyncTransport {
  readonly id = "relay";
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
    private readonly fetcher: typeof fetch = fetch,
  ) {
    if (!baseUrl.startsWith("https://")) throw new Error("relay requires HTTPS");
  }

  async pull(vaultId: string, sinceEtag?: string): Promise<OpaqueSnapshot | null> {
    assertVault(vaultId);
    const response = await this.fetcher(`${this.baseUrl}/v1/${vaultId}/manifest`, {
      headers: {
        authorization: `Bearer ${this.token}`,
        ...(sinceEtag === undefined ? {} : { "if-none-match": sinceEtag }),
      },
    });
    if (response.status === 304 || response.status === 404) return null;
    if (!response.ok) throw new Error(`relay pull failed: ${response.status}`);
    const etag = response.headers.get("etag");
    return { manifest: await readBounded(response), ...(etag === null ? {} : { etag }) };
  }

  async push(vaultId: string, manifest: Uint8Array, etag?: string): Promise<string | undefined> {
    assertVault(vaultId);
    assertSize(manifest);
    const response = await this.fetcher(`${this.baseUrl}/v1/${vaultId}/manifest`, {
      method: "PUT",
      headers: {
        authorization: `Bearer ${this.token}`,
        "content-type": "application/octet-stream",
        ...(etag === undefined ? {} : { "if-match": etag }),
      },
      body: manifest as unknown as BodyInit,
    });
    if (!response.ok) throw new Error(`relay push failed: ${response.status}`);
    return response.headers.get("etag") ?? undefined;
  }
}
