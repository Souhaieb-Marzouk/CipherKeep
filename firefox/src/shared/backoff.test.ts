import { describe, expect, it } from "vitest";
import { backoffSeconds, remainingSeconds } from "./backoff";

describe("unlock backoff", () => {
  it("does not delay the first four failures", () => {
    for (let attempt = 1; attempt <= 4; attempt += 1) {
      expect(backoffSeconds(attempt)).toBe(0);
    }
  });

  it("starts at 30 seconds on the fifth failure", () => {
    expect(backoffSeconds(5)).toBe(30);
    expect(backoffSeconds(6)).toBe(60);
    expect(backoffSeconds(7)).toBe(120);
  });

  it("caps the delay at one hour", () => {
    expect(backoffSeconds(50)).toBe(3_600);
  });

  it("saturates remaining time at zero", () => {
    expect(remainingSeconds(100, 40)).toBe(60);
    expect(remainingSeconds(100, 120)).toBe(0);
  });
});
