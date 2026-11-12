import { describe, expect, it } from "vitest";
import { EMPTY_SYNC_CONFIG, validateSyncConfig } from "./sync-config";

const webdav = { ...EMPTY_SYNC_CONFIG, endpoint: "https://dav.example/vault", username: "u", token: "x".repeat(20) };
const github = { ...EMPTY_SYNC_CONFIG, transport: "github" as const, owner: "o", repo: "r", branch: "main", token: "x".repeat(20) };

describe("sync configuration", () => {
  it("accepts HTTPS WebDAV and GitHub settings", () => {
    expect(validateSyncConfig(webdav)).toEqual([]);
    expect(validateSyncConfig(github)).toEqual([]);
  });

  it("rejects cleartext, embedded credentials, query tokens and traversal", () => {
    expect(validateSyncConfig({ ...webdav, endpoint: "http://dav.example" })).toContain("endpoint must use HTTPS");
    expect(validateSyncConfig({ ...webdav, endpoint: "https://u:p@dav.example" })).toContain("credentials must not be embedded in the URL");
    expect(validateSyncConfig({ ...webdav, endpoint: "https://dav.example/?token=x" })).toContain("endpoint must not contain query parameters");
    expect(validateSyncConfig({ ...github, owner: "../x" })).toContain("owner is invalid");
  });
});
