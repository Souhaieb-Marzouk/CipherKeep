import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { EncryptedStore, LockedError, STORE_VERSION } from "./store";

let dbName: string;
let store: EncryptedStore;

beforeEach(async () => {
  dbName = `cipherkeep-test-${crypto.randomUUID()}`;
  store = new EncryptedStore(dbName);
  await store.open();
});

afterEach(async () => {
  store.close();
  await new Promise<void>((resolve) => {
    const request = indexedDB.deleteDatabase(dbName);
    request.onsuccess = () => resolve();
    request.onerror = () => resolve();
    request.onblocked = () => resolve();
  });
});

describe("EncryptedStore", () => {
  it("starts locked and refuses reads", async () => {
    expect(store.locked).toBe(true);
    await expect(store.get("demo")).rejects.toBeInstanceOf(LockedError);
  });

  it("stores ciphertext, not the secret, and round-trips", async () => {
    await store.unlockDemo("shell-password");
    await store.put("demo", new TextEncoder().encode("test-secret-value"));

    const raw = await store.raw("demo");
    expect(raw).toBeDefined();
    expect(raw?.v).toBe(1);
    expect(new TextDecoder().decode(new Uint8Array(raw!.ciphertext))).not.toContain(
      "test-secret-value",
    );
    expect(JSON.stringify(raw)).not.toContain("test-secret-value");

    expect(new TextDecoder().decode(await store.get("demo"))).toBe("test-secret-value");
  });

  it("locks by dropping the in-memory key", async () => {
    await store.unlockDemo("shell-password");
    await store.put("demo", new TextEncoder().encode("secret"));
    store.lock();
    expect(store.locked).toBe(true);
    await expect(store.get("demo")).rejects.toBeInstanceOf(LockedError);
  });

  it("cannot decrypt old records after a restart generates a new key", async () => {
    await store.unlockDemo("shell-password");
    await store.put("demo", new TextEncoder().encode("secret"));
    store.close();

    const restarted = new EncryptedStore(dbName);
    await restarted.open();
    expect(restarted.locked).toBe(true);
    await restarted.unlockDemo("shell-password");
    await expect(restarted.get("demo")).rejects.toThrow();
    restarted.close();
  });

  it("serializes concurrent writers and keeps every id", async () => {
    await store.unlockDemo("shell-password");
    await Promise.all([
      store.put("a", new TextEncoder().encode("a")),
      store.put("b", new TextEncoder().encode("b")),
      store.put("c", new TextEncoder().encode("c")),
    ]);
    expect((await store.ids()).sort()).toEqual(["a", "b", "c"]);
  });

  it("has a stable versioned schema", async () => {
    expect(STORE_VERSION).toBe(1);
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(dbName);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    expect(db.objectStoreNames.contains("records")).toBe(true);
    db.close();
  });
});
