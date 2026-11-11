import { describe, expect, it } from "vitest";
import { isRequest } from "./protocol";

describe("request validation", () => {
  it("accepts a well-formed create request", () => {
    expect(isRequest({ type: "create", password: "correct horse battery staple" })).toBe(true);
  });

  it("rejects missing, empty or oversized passwords", () => {
    expect(isRequest({ type: "unlock" })).toBe(false);
    expect(isRequest({ type: "unlock", password: "" })).toBe(false);
    expect(isRequest({ type: "unlock", password: "x".repeat(1_025) })).toBe(false);
  });

  it("rejects malformed item fields and ids", () => {
    expect(isRequest({ type: "save-item", title: "", username: "", password: "x" })).toBe(false);
    expect(isRequest({ type: "delete-item", id: "../not-an-id" })).toBe(false);
    expect(isRequest({ type: "delete-item", id: "a".repeat(32) })).toBe(true);
  });

  it("rejects unknown request types", () => {
    expect(isRequest({ type: "drop-all" })).toBe(false);
  });
});
