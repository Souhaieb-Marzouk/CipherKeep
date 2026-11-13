import { afterEach, describe, expect, it, vi } from "vitest";

import {
  AliasProviderError,
  CloudflareAliasProvider,
  SimpleLoginAliasProvider,
} from "./alias-provider";

const token = "t".repeat(40);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("CloudflareAliasProvider", () => {
  it("creates and lists aliases through the Worker fixture", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push(init === undefined ? { url } : { url, init });
      if (init?.method === "POST") {
        return new Response(
          JSON.stringify({
            address: "site-abc@alias.test",
            provider: "cloudflare",
            providerId: "abc",
            label: "Site",
            enabled: true,
          }),
          { status: 201 },
        );
      }
      return new Response(JSON.stringify([]), { status: 200 });
    });

    const provider = new CloudflareAliasProvider("https://alias.example.com", token);
    const alias = await provider.create("Site");
    expect(alias.address).toBe("site-abc@alias.test");
    expect(calls[0]?.url).toBe("https://alias.example.com/aliases");
    expect((calls[0]?.init?.headers as Record<string, string>).authorization).toBe(`Bearer ${token}`);
    await provider.list();
    expect(calls[1]?.url).toBe("https://alias.example.com/aliases");
  });

  it("rejects HTTP endpoints", () => {
    expect(() => new CloudflareAliasProvider("http://alias.example.com", token)).toThrow(
      AliasProviderError,
    );
  });
});

describe("SimpleLoginAliasProvider", () => {
  it("creates an alias with the self-hosted API", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push(init === undefined ? { url } : { url, init });
      return new Response(
        JSON.stringify({
          alias: { id: "42", email: "site@sl.example", note: "Site", enabled: true },
        }),
        { status: 200 },
      );
    });

    const provider = new SimpleLoginAliasProvider("https://sl.example.com", token);
    const alias = await provider.create("Site");
    expect(alias).toEqual({
      address: "site@sl.example",
      provider: "simplelogin",
      providerId: "42",
      label: "Site",
      enabled: true,
    });
    expect(calls[0]?.url).toBe("https://sl.example.com/api/alias/create");
    expect((calls[0]?.init?.headers as Record<string, string>).authentication).toBe(token);
  });

  it("disables only enabled aliases and deletes by provider id", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push(init === undefined ? { url } : { url, init });
      if (url.endsWith("/api/aliases")) {
        return new Response(
          JSON.stringify({
            aliases: [
              { id: "42", email: "site@sl.example", note: "Site", enabled: true },
            ],
          }),
          { status: 200 },
        );
      }
      return new Response("{}", { status: 200 });
    });

    const provider = new SimpleLoginAliasProvider("https://sl.example.com", token);
    await provider.disable("site@sl.example");
    expect(calls[1]?.url).toBe("https://sl.example.com/api/alias/toggle/42");
    await provider.delete("site@sl.example");
    expect(calls[3]?.url).toBe("https://sl.example.com/api/alias/42");
    expect(calls[3]?.init?.method).toBe("DELETE");
  });

  it("rejects missing aliases and malformed lists", async () => {
    vi.stubGlobal("fetch", async () => new Response(JSON.stringify({ aliases: [] }), { status: 200 }));
    const provider = new SimpleLoginAliasProvider("https://sl.example.com", token);
    await expect(provider.delete("missing@sl.example")).rejects.toThrow(AliasProviderError);

    vi.stubGlobal("fetch", async () => new Response(JSON.stringify({ aliases: "bad" }), { status: 200 }));
    await expect(provider.list()).rejects.toThrow(AliasProviderError);
  });
});
