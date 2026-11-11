import { describe, expect, it, vi } from "vitest";
import { AutoLock } from "./auto-lock";
import { copySecret } from "./clipboard";

describe("AutoLock", () => {
  it("locks after the timeout", () => {
    const policy = new AutoLock(60_000, 0);
    expect(policy.shouldLock(59_999)).toBe(false);
    expect(policy.shouldLock(60_000)).toBe(true);
    policy.lock();
    expect(policy.locked).toBe(true);
  });

  it("activity extends the deadline", () => {
    const policy = new AutoLock(60_000, 0);
    policy.recordActivity(30_000);
    expect(policy.remaining(30_000)).toBe(60_000);
    expect(policy.shouldLock(89_999)).toBe(false);
    expect(policy.shouldLock(90_000)).toBe(true);
  });
});

describe("clipboard", () => {
  it("clears after the timeout", async () => {
    const writes: string[] = [];
    vi.stubGlobal("navigator", {
      clipboard: { writeText: async (value: string) => writes.push(value) },
    });
    try {
      vi.useFakeTimers();
      await copySecret("secret", 30_000);
      expect(writes).toEqual(["secret"]);
      await vi.advanceTimersByTimeAsync(30_000);
      expect(writes).toEqual(["secret", ""]);
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
    }
  });
});
