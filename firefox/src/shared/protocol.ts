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
  | { type: "save-item"; title: string; username: string; password: string }
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
  | { type: "totp-code"; uri: string };

export interface ItemView {
  id: string;
  title: string;
  username: string;
  password: string;
  totpUri: string;
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
        isText(request.password, 4_096)
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
  }
}
