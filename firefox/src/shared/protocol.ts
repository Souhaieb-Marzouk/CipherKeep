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
  | { type: "delete-item"; id: string };

export interface ItemView {
  id: string;
  title: string;
  username: string;
  password: string;
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
  | { ok: false; error: string; code?: number };

export function isRequest(value: unknown): value is Request {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const type = (value as { type?: unknown }).type;
  return (
    type === "status" ||
    type === "check-updates" ||
    type === "create" ||
    type === "unlock" ||
    type === "lock" ||
    type === "activity" ||
    type === "recover" ||
    type === "list-items" ||
    type === "save-item" ||
    type === "delete-item"
  );
}
