import { describe, expect, it, vi } from "vitest";
import { GitHubTransport, RelayTransport, WebDavTransport } from "./sync-transport";

const vault = "a".repeat(32);
const bytes = new Uint8Array([1, 2, 3]);

describe("opaque sync transports", () => {
  it("WebDAV uses HTTPS, ETag and opaque bytes", async () => {
    const fetcher = vi.fn(async (_url: string, _init?: RequestInit) => new Response(bytes, { status: 200, headers: { etag: "\"1\"" } }));
    const transport = new WebDavTransport("https://dav.example", "u", "p", fetcher as typeof fetch);
    const snapshot = await transport.pull(vault);
    expect(snapshot?.etag).toBe("\"1\"");
    expect(snapshot?.manifest).toEqual(bytes);

    const put = vi.fn(async (_url: string, _init?: RequestInit) => new Response(null, { status: 201, headers: { etag: "\"2\"" } }));
    const writer = new WebDavTransport("https://dav.example", "u", "p", put as typeof fetch);
    await expect(writer.push(vault, bytes, "\"1\"")).resolves.toBe("\"2\"");
    expect(put.mock.calls[0]?.[1]?.method).toBe("PUT");
  });

  it("rejects cleartext and traversal ids", async () => {
    expect(() => new WebDavTransport("http://dav.example", "u", "p")).toThrow();
    const fetcher = vi.fn();
    const transport = new RelayTransport("https://relay.example", "x".repeat(32), fetcher as typeof fetch);
    await expect(transport.pull("../etc/passwd")).rejects.toThrow("invalid vault id");
  });

  it("GitHub uses a single conditional commit", async () => {
    const fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        return new Response(JSON.stringify({ content: { sha: "new" } }), { status: 200 });
      }
      return new Response(JSON.stringify({ content: btoa("\u0001\u0002\u0003"), sha: "old" }), { status: 200 });
    });
    const transport = new GitHubTransport("o", "r", "main", "t", fetcher as typeof fetch);
    await expect(transport.push(vault, bytes, "old")).resolves.toBe("new");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
