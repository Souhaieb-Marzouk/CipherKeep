import { describe, expect, it } from "vitest";
import { isNewer, parseVersion } from "./version";

describe("version", () => {
  it("compares newer, equal and older", () => {
    expect(isNewer("0.1.0", "0.2.0")).toBe(true);
    expect(isNewer("1.9.9", "1.10.0")).toBe(true);
    expect(isNewer("1.2.3", "1.2.3")).toBe(false);
    expect(isNewer("2.0.0", "1.9.9")).toBe(false);
  });

  it("accepts v-prefix and prerelease suffixes", () => {
    expect(isNewer("v0.1.0", "v0.1.1-rc.1")).toBe(true);
    expect(isNewer("0.1.0-shell", "0.1.1")).toBe(true);
  });

  it("rejects malformed versions", () => {
    expect(parseVersion("release-1")).toBeNull();
    expect(parseVersion("1.2")).toBeNull();
    expect(isNewer("0.1.0", "bad")).toBe(false);
  });
});
