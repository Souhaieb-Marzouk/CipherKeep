import { afterEach, describe, expect, it, vi } from "vitest";

import { HibpClient, HibpError, hashPassword } from "./hibp";

afterEach(() => vi.unstubAllGlobals());

describe("HibpClient", () => {
  it("hashes locally to a five-character prefix", async () => {
    const { prefix, suffix } = await hashPassword("password");
    expect(prefix).toBe("5BAA6");
    expect(prefix).toHaveLength(5);
    expect(suffix).toHaveLength(35);
  });

  it("sends only the prefix and matches the suffix locally", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", async (url: string) => {
      calls.push(url);
      return new Response(
        "1E4C9B93F3F0682250B6CF8331B7EE68FD8:12345\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:1\r\n",
        { status: 200 },
      );
    });
    const client = new HibpClient();
    const result = await client.check("password");
    expect(result.count).toBe(12_345);
    expect(calls[0]).toBe("https://api.pwnedpasswords.com/range/5BAA6");
    expect(calls[0]).not.toContain("1E4C9");
  });

  it("returns zero for an unknown suffix", async () => {
    vi.stubGlobal("fetch", async () => new Response("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:9\n", { status: 200 }));
    const client = new HibpClient();
    expect((await client.check("password")).count).toBe(0);
  });

  it("handles provider errors and clears cache", async () => {
    vi.stubGlobal("fetch", async () => new Response("nope", { status: 503 }));
    const client = new HibpClient();
    await expect(client.check("password")).rejects.toThrow(HibpError);
    client.clear();
  });
});
