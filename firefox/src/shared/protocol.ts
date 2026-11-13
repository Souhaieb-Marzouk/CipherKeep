import type { UpdateReport } from "../background/updates";

export interface AliasView {
  id: string;
  address: string;
  provider: string;
  providerId: string;
  label: string;
  enabled: boolean;
}

export type Request =
  | { type: "status" }
  | { type: "check-updates" }
  | { type: "create"; password: string }
  | { type: "unlock"; password: string }
  | { type: "lock" }
  | { type: "activity" }
  | { type: "recover"; first: string; second: string; newPassword: string }
  | { type: "list-items" }
  | {
      type: "save-item";
      title: string;
      username: string;
      password: string;
      url?: string;
      notes?: string;
      folder?: string;
      tags?: string[];
      favorite?: boolean;
      autofillAllowed?: boolean;
    }
  | { type: "delete-item"; id: string }
  | {
      type: "generate-password";
      length: number;
      lowercase: boolean;
      uppercase: boolean;
      digits: boolean;
      symbols: boolean;
      excludeAmbiguous: boolean;
    }
  | { type: "generate-passphrase"; words: number; separator: string }
  | { type: "save-totp-item"; title: string; account: string; uri: string }
  | { type: "totp-code"; uri: string }
  | { type: "search"; query: string }
  | { type: "create-folder"; name: string }
  | { type: "rename-folder"; oldName: string; newName: string }
  | { type: "delete-folder"; name: string }
  | { type: "export-backup"; password: string }
  | { type: "import-backup"; password: string; data: string }
  | { type: "autofill-request"; id: string }
  | { type: "consume-autofill"; token: string }
  | { type: "inspect-autofill" }
  | { type: "recipe-status" }
  | { type: "sync-vault-id" }
  | { type: "sync-now" }
  | { type: "resolve-conflict"; item: string; field: number; keepRight: boolean }
  | { type: "export-sync-bundle" }
  | { type: "import-sync-bundle"; json: string }
  | { type: "create-alias"; label: string }
  | { type: "list-aliases" }
  | { type: "disable-alias"; address: string }
  | { type: "delete-alias"; address: string; id: string }
  | {
      type: "arm-emergency";
      cronUrl: string;
      token: string;
      contacts: Array<{ id: string; address: string }>;
      inactivityDays: number;
      graceMinutes: number;
    }
  | { type: "emergency-status" }
  | { type: "checkin-emergency" }
  | { type: "cancel-emergency" }
  | { type: "enter-travel" }
  | { type: "allow-phishing-origin"; origin: string }
  | { type: "clear-phishing-overrides" }
  | {
      type: "update-item";
      id: string;
      title: string;
      username: string;
      password: string;
      url: string;
      notes: string;
      folder: string;
      tags: string[];
      favorite: boolean;
      autofillAllowed: boolean;
    };

export interface SearchHit {
  id: string;
  score: number;
  snippet: string;
}

export interface ItemView {
  id: string;
  title: string;
  username: string;
  password: string;
  totpUri: string;
  url: string;
  notes: string;
  folder: string;
  tags: string[];
  favorite: boolean;
  autofillAllowed: boolean;
  updatedAt: number;
}

export type Response =
  | {
      ok: true;
      kind: "status";
      hasVault: boolean;
      locked: boolean;
      retryAfter: number;
    }
  | { ok: true; kind: "ok"; shares?: string[]; imported?: number }
  | { ok: true; kind: "update"; update: UpdateReport }
  | { ok: true; kind: "items"; items: ItemView[] }
  | {
      ok: true;
      kind: "generated";
      value: string;
      entropyBits: number;
      strength: string;
    }
  | { ok: true; kind: "totp"; code: string; secondsRemaining: number }
  | { ok: true; kind: "search"; hits: SearchHit[] }
  | { ok: true; kind: "backup"; data: string }
  | { ok: true; kind: "autofill-token"; token: string }
  | { ok: true; kind: "recipes"; version: number; hash: string }
  | { ok: true; kind: "vault-id"; vaultId: string }
  | { ok: true; kind: "sync"; records: number; conflicts: number }
  | { ok: true; kind: "aliases"; aliases: AliasView[] }
  | {
      ok: true;
      kind: "emergency";
      status: {
        id: string;
        state: string;
        lastCheckIn: number;
        inactivitySecs: number;
        graceSecs: number;
      };
      contactShares?: Array<{ id: string; share: string }>;
    }
  | { ok: true; kind: "conflict-resolved" }
  | { ok: true; kind: "bundle"; json: string }
  | { ok: true; kind: "sync"; records: number; conflicts: number }
  | { ok: true; kind: "aliases"; aliases: AliasView[] }
  | {
      ok: true;
      kind: "emergency";
      status: {
        id: string;
        state: string;
        lastCheckIn: number;
        inactivitySecs: number;
        graceSecs: number;
      };
      contactShares?: Array<{ id: string; share: string }>;
    }
  | {
      ok: true;
      kind: "inspection";
      origin: string;
      locked: boolean;
      matching: number;
      consented: number;
    }
  | {
      ok: true;
      kind: "credentials";
      username: string;
      password: string;
      usernameSelector?: string;
      passwordSelector?: string;
    }
  | { ok: false; error: string; code?: number };

function isText(value: unknown, max: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= max;
}

function isRequestType(value: unknown): value is Request["type"] {
  return (
    typeof value === "string" &&
    [
      "status",
      "check-updates",
      "create",
      "unlock",
      "lock",
      "activity",
      "recover",
      "list-items",
      "save-item",
      "delete-item",
      "generate-password",
      "generate-passphrase",
      "save-totp-item",
      "totp-code",
      "search",
      "create-folder",
      "rename-folder",
      "delete-folder",
      "export-backup",
      "import-backup",
      "autofill-request",
      "consume-autofill",
      "inspect-autofill",
      "recipe-status",
      "sync-vault-id",
      "sync-now",
      "resolve-conflict",
      "export-sync-bundle",
      "import-sync-bundle",
      "create-alias",
      "list-aliases",
      "disable-alias",
      "delete-alias",
      "arm-emergency",
      "emergency-status",
      "checkin-emergency",
      "cancel-emergency",
      "enter-travel",
      "allow-phishing-origin",
      "clear-phishing-overrides",
      "update-item",
    ].includes(value)
  );
}

export function isRequest(value: unknown): value is Request {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const request = value as Record<string, unknown>;
  if (!isRequestType(request.type)) {
    return false;
  }
  switch (request.type) {
    case "status":
    case "check-updates":
    case "lock":
    case "activity":
    case "list-items":
      return true;
    case "create":
    case "unlock":
      return isText(request.password, 1_024);
    case "recover":
      return (
        isText(request.first, 4_096) &&
        isText(request.second, 4_096) &&
        isText(request.newPassword, 1_024)
      );
    case "save-item":
      return (
        isText(request.title, 512) &&
        isText(request.username, 512) &&
        isText(request.password, 4_096) &&
        (request.url === undefined ||
          (typeof request.url === "string" && request.url.length <= 2_048)) &&
        (request.notes === undefined ||
          (typeof request.notes === "string" && request.notes.length <= 65_536)) &&
        (request.folder === undefined ||
          (typeof request.folder === "string" && request.folder.length <= 512)) &&
        (request.tags === undefined ||
          (Array.isArray(request.tags) &&
            request.tags.length <= 64 &&
            request.tags.every(
              (tag) => typeof tag === "string" && tag.length > 0 && tag.length <= 64,
            ))) &&
        (request.favorite === undefined || typeof request.favorite === "boolean") &&
        (request.autofillAllowed === undefined || typeof request.autofillAllowed === "boolean")
      );
    case "delete-item":
      return typeof request.id === "string" && /^[0-9a-f]{32}$/i.test(request.id);
    case "generate-password":
      return (
        typeof request.length === "number" &&
        Number.isInteger(request.length) &&
        request.length >= 8 &&
        request.length <= 128 &&
        typeof request.lowercase === "boolean" &&
        typeof request.uppercase === "boolean" &&
        typeof request.digits === "boolean" &&
        typeof request.symbols === "boolean" &&
        typeof request.excludeAmbiguous === "boolean" &&
        (request.lowercase || request.uppercase || request.digits || request.symbols)
      );
    case "generate-passphrase":
      return (
        typeof request.words === "number" &&
        Number.isInteger(request.words) &&
        request.words >= 3 &&
        request.words <= 12 &&
        typeof request.separator === "string" &&
        [...request.separator].length === 1
      );
    case "save-totp-item":
      return (
        isText(request.title, 512) &&
        isText(request.account, 512) &&
        isText(request.uri, 4_096) &&
        request.uri.startsWith("otpauth://totp/")
      );
    case "totp-code":
      return (
        isText(request.uri, 4_096) &&
        request.uri.startsWith("otpauth://totp/")
      );
    case "search":
      return typeof request.query === "string" && request.query.length <= 256;
    case "create-folder":
      return isText(request.name, 64);
    case "rename-folder":
      return isText(request.oldName, 64) && isText(request.newName, 64);
    case "delete-folder":
      return isText(request.name, 64);
    case "export-backup":
      return isText(request.password, 1_024) && request.password.length >= 12;
    case "update-item":
      return (
        typeof request.id === "string" &&
        /^[0-9a-f]{32}$/i.test(request.id) &&
        isText(request.title, 512) &&
        typeof request.username === "string" &&
        typeof request.password === "string" &&
        typeof request.url === "string" &&
        typeof request.notes === "string" &&
        typeof request.folder === "string" &&
        Array.isArray(request.tags) &&
        typeof request.favorite === "boolean"
      );
    case "export-sync-bundle":
    case "create-alias":
      return isText(request.label, 128);
    case "list-aliases":
      return true;
    case "disable-alias":
      return typeof request.address === "string" && request.address.length <= 320;
    case "arm-emergency":
      return (
        typeof request.cronUrl === "string" &&
        request.cronUrl.length <= 2_048 &&
        typeof request.token === "string" &&
        request.token.length <= 4_096 &&
        Array.isArray(request.contacts) &&
        request.contacts.length >= 1 &&
        request.contacts.length <= 2 &&
        request.contacts.every(
          (contact) =>
            typeof contact === "object" &&
            contact !== null &&
            typeof (contact as { id?: unknown }).id === "string" &&
            typeof (contact as { address?: unknown }).address === "string",
        ) &&
        typeof request.inactivityDays === "number" &&
        typeof request.graceMinutes === "number"
      );
    case "enter-travel":
      return true;
    case "clear-phishing-overrides":
      return true;
    case "allow-phishing-origin":
      return typeof request.origin === "string" && request.origin.length <= 253;
    case "emergency-status":
    case "checkin-emergency":
    case "cancel-emergency":
      return true;
    case "delete-alias":
      return (
        typeof request.address === "string" &&
        request.address.length <= 320 &&
        typeof request.id === "string" &&
        /^[0-9a-f]{32}$/i.test(request.id)
      );
    case "import-sync-bundle":
      return true;
    case "resolve-conflict":
      return true;
    case "sync-now":
      return true;
    case "sync-vault-id":
      return true;
    case "recipe-status":
      return true;
    case "inspect-autofill":
      return true;
    case "consume-autofill":
      return typeof request.token === "string" && request.token.length <= 128;
    case "autofill-request":
      return typeof request.id === "string" && /^[0-9a-f]{32}$/i.test(request.id);
    case "import-backup":
      return (
        isText(request.password, 1_024) &&
        request.password.length >= 12 &&
        typeof request.data === "string" &&
        request.data.length <= 48_000_000
      );
  }
}
