/** IndexedDB store for already-encrypted item ciphertext. */
export interface CiphertextRecord {
  id: string;
  ciphertext: Uint8Array;
  updatedAt: number;
}

const DB_VERSION = 1;
const STORE = "items";

export class CiphertextStore {
  #db: IDBDatabase | null = null;

  constructor(readonly dbName = "cipherkeep-items") {}

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

  async put(record: CiphertextRecord): Promise<void> {
    await this.#withStore("readwrite", (store) => store.put(record));
  }

  async list(): Promise<CiphertextRecord[]> {
    return this.#withStore("readonly", (store) => store.getAll());
  }

  async delete(id: string): Promise<void> {
    await this.#withStore("readwrite", (store) => store.delete(id));
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
