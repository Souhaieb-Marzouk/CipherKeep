/** IndexedDB record shape. No plaintext field is ever stored. */
export interface EncryptedRecord {
  id: string;
  v: 1;
  iv: ArrayBuffer;
  ciphertext: ArrayBuffer;
  lamport: number;
  tombstone: boolean;
  updatedAt: number;
}

export class LockedError extends Error {
  constructor() {
    super("vault is locked");
    this.name = "LockedError";
  }
}

export class ItemMissingError extends Error {
  constructor(id: string) {
    super(`item not found: ${id}`);
    this.name = "ItemMissingError";
  }
}

const DB_VERSION = 1;
const STORE = "records";

/**
 * Shell-only encrypted IndexedDB store.
 *
 * The AES-GCM key is generated in background memory and is never written to
 * IndexedDB or storage. Closing the background page therefore loses the key and
 * returns the vault to the locked state. The shared session replaces this demo key with the
 * real master-password keyring.
 */
export class EncryptedStore {
  #db: IDBDatabase | null = null;
  #key: CryptoKey | null = null;
  #queue: Promise<unknown> = Promise.resolve();

  constructor(readonly dbName = "cipherkeep") {}

  get locked(): boolean {
    return this.#key === null;
  }

  async open(): Promise<void> {
    if (this.#db) {
      return;
    }
    this.#db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(this.dbName, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "id" });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("IndexedDB open failed"));
      request.onblocked = () => reject(new Error("IndexedDB upgrade blocked"));
    });
  }

  /** Demo unlock: any non-empty password derives no real key yet. */
  async unlockDemo(password: string): Promise<void> {
    if (password.length === 0) {
      throw new Error("master password required");
    }
    this.#key = await crypto.subtle.generateKey(
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"],
    );
  }

  lock(): void {
    this.#key = null;
  }

  async put(id: string, plaintext: Uint8Array, lamport = 1, tombstone = false): Promise<void> {
    return this.#enqueue(async () => {
      const key = this.#requireKey();
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ciphertext = await crypto.subtle.encrypt(
        { name: "AES-GCM", iv: iv.buffer as ArrayBuffer },
        key,
        plaintext as BufferSource,
      );
      const record: EncryptedRecord = {
        id,
        v: 1,
        iv: iv.buffer,
        ciphertext,
        lamport,
        tombstone,
        updatedAt: Date.now(),
      };
      await this.#withStore("readwrite", (store) => store.put(record));
    });
  }

  async get(id: string): Promise<Uint8Array> {
    const key = this.#requireKey();
    const record = await this.#withStore("readonly", (store) => store.get(id));
    if (!record) {
      throw new ItemMissingError(id);
    }
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: record.iv },
      key,
      record.ciphertext,
    );
    return new Uint8Array(plaintext);
  }

  async ids(): Promise<string[]> {
    const keys = await this.#withStore("readonly", (store) => store.getAllKeys());
    return keys.map(String);
  }

  async raw(id: string): Promise<EncryptedRecord | undefined> {
    return this.#withStore("readonly", (store) => store.get(id));
  }

  async clear(): Promise<void> {
    return this.#enqueue(async () => {
      await this.#withStore("readwrite", (store) => store.clear());
    });
  }

  close(): void {
    this.#db?.close();
    this.#db = null;
    this.#key = null;
  }

  #requireKey(): CryptoKey {
    if (!this.#key) {
      throw new LockedError();
    }
    return this.#key;
  }

  #enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const run = this.#queue.then(operation, operation);
    this.#queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async #withStore<T>(
    mode: IDBTransactionMode,
    operation: (store: IDBObjectStore) => IDBRequest<T>,
  ): Promise<T> {
    if (!this.#db) {
      throw new Error("store is not open");
    }
    return new Promise<T>((resolve, reject) => {
      const transaction = this.#db!.transaction(STORE, mode);
      const request = operation(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB transaction failed"));
      transaction.onabort = () => reject(transaction.error ?? new Error("IndexedDB transaction aborted"));
    });
  }
}

export const STORE_VERSION = DB_VERSION;
