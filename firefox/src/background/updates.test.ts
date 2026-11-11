import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { checkForUpdates } from "../background/updates";

const json = JSON.stringify({
  tag_name: "v0.2.0",
  published_at: "2026-11-11T00:00:00Z",
  body: "<b>Changes</b><script>alert(1)</script>",
  html_url: "https://github.com/Souhaieb-Marzouk/CipherKeep/releases/tag/v0.2.0",
  assets: [{ size: 100 }, { size: 200 }],
});

describe("checkForUpdates", () => {
  let stored: Record<string, string>;

  beforeEach(() => {
    stored = {};
    vi.stubGlobal("browser", {
      storage: {
        local: {
          get: async (keys: string[]) => Object.fromEntries(keys.map((key) => [key, stored[key]])),
          set: async (values: Record<string, string>) => {
            Object.assign(stored, values);
          },
        },
      },
      runtime: { getManifest: () => ({ version: "0.1.0" }) },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns a sanitized available update and caches ETag", async () => {
    const fetchImpl = vi.fn(async () =>
      new Response(json, { status: 200, headers: { ETag: '"abc"' } }),
    ) as unknown as typeof fetch;
    const report = await checkForUpdates(fetchImpl, "0.1.0");
    expect(report.kind).toBe("available");
    if (report.kind === "available") {
      expect(report.latestVersion).toBe("v0.2.0");
      expect(report.sizeBytes).toBe(300);
      expect(report.notes).not.toContain("<script>");
    }
    expect(stored.updateEtag).toBe('"abc"');
  });

  it("uses cached body on 304", async () => {
    stored.updateEtag = '"abc"';
    stored.updateBody = json;
    let sentConditional = "";
    const fetchImpl = vi.fn(async (_url: string, init?: RequestInit) => {
      sentConditional = new Headers(init?.headers).get("If-None-Match") ?? "";
      return new Response(null, { status: 304 });
    }) as unknown as typeof fetch;
    const report = await checkForUpdates(fetchImpl, "0.1.0");
    expect(report.kind).toBe("available");
    expect(sentConditional).toBe('"abc"');
  });

  it("reports rate limiting and malformed JSON", async () => {
    const limited = vi.fn(async () => new Response("rate", { status: 403 })) as unknown as typeof fetch;
    expect((await checkForUpdates(limited, "0.1.0")).kind).toBe("rate-limited");

    const malformed = vi.fn(async () => new Response("{bad", { status: 200 })) as unknown as typeof fetch;
    expect((await checkForUpdates(malformed, "0.1.0")).kind).toBe("error");
  });
});
