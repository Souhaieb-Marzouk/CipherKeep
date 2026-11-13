import { afterEach, describe, expect, it, vi } from "vitest";

import { EmergencyClientError, EmergencyCronClient } from "./emergency-client";

const token = "e".repeat(40);
const draft = {
  contacts: [{ id: "c1", address: "alice@example.test" }],
  shares: [{ contactId: "c1", ciphertext: "opaque" }],
  inactivitySecs: 100,
  reminderLeadSecs: 10,
  graceSecs: 10,
};

afterEach(() => vi.unstubAllGlobals());

describe("EmergencyCronClient", () => {
  it("creates a policy with a bearer token", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push(init === undefined ? { url } : { url, init });
      return new Response(
        JSON.stringify({ id: "p1", state: "armed", lastCheckIn: 1, ...draft }),
        { status: 201 },
      );
    });
    const client = new EmergencyCronClient("https://cron.example.com", token);
    const status = await client.create(draft);
    expect(status.id).toBe("p1");
    expect(calls[0]?.url).toBe("https://cron.example.com/policies");
    expect((calls[0]?.init?.headers as Record<string, string>).authorization).toBe(`Bearer ${token}`);
  });

  it("supports check-in and cancel paths", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", async (url: string) => {
      urls.push(url);
      return new Response(JSON.stringify({ id: "p1", state: "armed", ...draft }), { status: 200 });
    });
    const client = new EmergencyCronClient("https://cron.example.com", token);
    await client.checkIn("p1");
    await client.cancel("p1");
    expect(urls).toEqual([
      "https://cron.example.com/policies/p1/checkin",
      "https://cron.example.com/policies/p1/cancel",
    ]);
  });

  it("rejects HTTP and oversized/invalid policies", async () => {
    expect(() => new EmergencyCronClient("http://cron.example.com", token)).toThrow(EmergencyClientError);
    const client = new EmergencyCronClient("https://cron.example.com", token);
    await expect(client.create({ ...draft, contacts: [], shares: [] })).rejects.toThrow(
      EmergencyClientError,
    );
  });

  it("rejects invalid JSON responses", async () => {
    vi.stubGlobal("fetch", async () => new Response("not-json", { status: 200 }));
    const client = new EmergencyCronClient("https://cron.example.com", token);
    await expect(client.status("p1")).rejects.toThrow(EmergencyClientError);
  });
});
