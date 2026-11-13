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

declare function export_portable(itemsJson: Uint8Array, password: string): Uint8Array;
declare function import_portable(bytes: Uint8Array, password: string): Uint8Array;

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
  static create_shared(password: Uint8Array, vaultId: Uint8Array, vaultKey: Uint8Array): WasmVaultSession;
  static open(blob: Uint8Array): WasmVaultSession;
  static unlock(blob: Uint8Array, password: Uint8Array): WasmVaultSession;
  static recover(
    blob: Uint8Array,
    first: string,
    second: string,
    newPassword: Uint8Array,
  ): WasmVaultSession;

  encrypt_item(itemId: Uint8Array, plaintext: Uint8Array): Uint8Array;
  vault_id(): Uint8Array;
  migrate_item(itemId: Uint8Array, ciphertext: Uint8Array, actor: Uint8Array, created_at: number, counter_start: number): Uint8Array;
  export_sync_record(itemId: Uint8Array, ciphertext: Uint8Array, actor: Uint8Array, created_at: number, counter_start: number): Uint8Array;
  family_vault_key(): Uint8Array;
  webauthn_seal(prfOutput: Uint8Array): Uint8Array;
  webauthn_open(sealed: Uint8Array, prfOutput: Uint8Array): void;
  rotate_family_key(itemsJson: string): string;
  apply_sync_record(syncJson: Uint8Array): Uint8Array;
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

declare function merge_sync_records(local: string[], remote: string[]): string;
declare function merge_sync_records_v2(local: string[], remote: string[]): string;

declare function resolve_sync_conflict(local: string, remote: string, fieldId: number, keepRight: boolean): string;

declare function phishing_check(url: string): string;

declare function health_report(itemsJson: string, now: bigint, staleSecs: bigint): string;


declare function family_identity(id: string, role: string): string;
declare function family_wrap(vaultId: string, vaultKey: string, memberId: string, memberPublic: string, role: string): string;
declare function family_unwrap(vaultId: string, memberId: string, ephemeralPublic: string, nonce: string, ciphertext: string, role: string, secret: string): string;
