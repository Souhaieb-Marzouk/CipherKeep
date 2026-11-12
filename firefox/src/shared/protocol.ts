import type { UpdateReport } from "../background/updates";

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
  | { type: "rename-folder"; oldName: string; newName: string }
  | { type: "delete-folder"; name: string };

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
}

export type Response =
  | {
      ok: true;
      kind: "status";
      hasVault: boolean;
      locked: boolean;
      retryAfter: number;
    }
  | { ok: true; kind: "ok"; shares?: string[] }
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
      "rename-folder",
      "delete-folder",
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
        (request.favorite === undefined || typeof request.favorite === "boolean")
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
    case "rename-folder":
      return isText(request.oldName, 64) && isText(request.newName, 64);
    case "delete-folder":
      return isText(request.name, 64);
  }
}
