import { describe, expect, it } from "vitest";
import { canonicalJson, parseRegistry, recipeForOrigin, verifyRegistry } from "./recipes";

const registry = {
  version: 3,
  recipes: [
    {
      origin: "example.com",
      usernameSelector: "#user",
      passwordSelector: "#pass",
      submitSelector: "button[type=submit]",
    },
  ],
};

async function signed() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicKey = await crypto.subtle.exportKey("jwk", pair.publicKey);
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(canonicalJson(parseRegistry(registry))),
  );
  return {
    publicKey: { kty: "EC", crv: "P-256", x: publicKey.x!, y: publicKey.y! } as const,
    signature: btoa(String.fromCharCode(...new Uint8Array(signature))),
  };
}

describe("signed recipe registry", () => {
  it("verifies a canonical registry and finds an exact origin", async () => {
    const { publicKey, signature } = await signed();
    const parsed = await verifyRegistry(JSON.stringify(registry), signature, publicKey);
    expect(parsed.version).toBe(3);
    expect(recipeForOrigin(parsed, "EXAMPLE.com")?.passwordSelector).toBe("#pass");
  });

  it("rejects tampered JSON and wrong keys", async () => {
    const { publicKey, signature } = await signed();
    const tampered = JSON.stringify({ ...registry, version: 4 });
    await expect(verifyRegistry(tampered, signature, publicKey)).rejects.toThrow();
    const other = await signed();
    await expect(verifyRegistry(JSON.stringify(registry), signature, other.publicKey)).rejects.toThrow();
  });

  it("rejects scripts and cross-origin lookalikes", () => {
    expect(() => parseRegistry({ version: 1, recipes: [{ origin: "https://example.com", usernameSelector: "#u", passwordSelector: "#p" }] })).toThrow();
    expect(() => parseRegistry({ version: 1, recipes: [{ origin: "example.com", usernameSelector: "javascript:alert(1)", passwordSelector: "#p" }] })).toThrow();
    expect(() => parseRegistry({ version: 0, recipes: [] })).toThrow();
  });
});
