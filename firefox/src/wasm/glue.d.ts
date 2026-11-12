/**
 * Minimal declarations for the generated wasm-bindgen no-modules glue.
 *
 * The glue is prepended to the background bundle by esbuild; it defines the
 * global `wasm_bindgen` initializer and the exported `WasmVaultSession` class.
 */
declare const wasm_bindgen: {
  (input?: string | URL | Request): Promise<unknown>;
  WasmVaultSession: typeof WasmVaultSession;
};

interface WasmGeneratedSecret {
  value: string;
  entropy_bits: number;
  strength: string;
}

declare function generate_password(
  length: number,
  lowercase: boolean,
  uppercase: boolean,
  digits: boolean,
  symbols: boolean,
  excludeAmbiguous: boolean,
): WasmGeneratedSecret;

declare function generate_passphrase(words: number, separator: string): WasmGeneratedSecret;

interface WasmTotpCode {
  code: string;
  seconds_remaining: number;
}

declare function totp_code(uri: string, unixSecs: number): WasmTotpCode;

declare class WasmSearchIndex {
  constructor();
  insert(
    id: Uint8Array,
    title: string,
    username: string,
    url: string,
    folder: string,
    notes: string,
  ): void;
  remove(id: Uint8Array): boolean;
  clear(): void;
  search(query: string, limit: number): Array<{ id: string; score: number; snippet: string }>;
  len(): number;
}

declare class WasmVaultSession {
  static create(password: Uint8Array): WasmVaultSession;
  static open(blob: Uint8Array): WasmVaultSession;
  static unlock(blob: Uint8Array, password: Uint8Array): WasmVaultSession;
  static recover(
    blob: Uint8Array,
    first: string,
    second: string,
    newPassword: Uint8Array,
  ): WasmVaultSession;

  encrypt_item(itemId: Uint8Array, plaintext: Uint8Array): Uint8Array;
  decrypt_item(itemId: Uint8Array, ciphertext: Uint8Array): Uint8Array;
  save_item(itemId: Uint8Array, plaintext: Uint8Array): void;
  get_item(itemId: Uint8Array): Uint8Array;
  list_item_ids(): string[];
  delete_item(itemId: Uint8Array): void;
  item_count(): number;
  blob(): Uint8Array;
  recovery_shares(): string[];
  lock(): void;
  is_locked(): boolean;
  failed_attempts(): number;
  retry_after(): number;
  unlock_again(password: Uint8Array): void;
}
