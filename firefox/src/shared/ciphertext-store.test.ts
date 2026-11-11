import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { CiphertextStore } from "./ciphertext-store";

let dbName: string;
let store: CiphertextStore;

beforeEach(async () => {
  dbName = `cipherkeep-items-${crypto.randomUUID()}`;
  store = new CiphertextStore(dbName);
  await store.open();
});

afterEach(async () => {
  await new Promise<void>((resolve) => {
    const request = indexedDB.deleteDatabase(dbName);
    request.onsuccess = () => resolve();
    request.onerror = () => resolve();
    request.onblocked = () => resolve();
  });
});

describe("CiphertextStore", () => {
  it("stores and lists ciphertext records", async () => {
    await store.put({ id: "a", ciphertext: new Uint8Array([1, 2, 3]), updatedAt: 1 });
    const records = await store.list();
    expect(records).toHaveLength(1);
    expect(Array.from(records[0]!.ciphertext)).toEqual([1, 2, 3]);
  });

  it("deletes a record", async () => {
    await store.put({ id: "a", ciphertext: new Uint8Array([1]), updatedAt: 1 });
    await store.delete("a");
    expect(await store.list()).toHaveLength(0);
  });

  it("isolates databases", async () => {
    await store.put({ id: "a", ciphertext: new Uint8Array([1]), updatedAt: 1 });
    const other = new CiphertextStore(`${dbName}-other`);
    await other.open();
    expect(await other.list()).toHaveLength(0);
  });
});
