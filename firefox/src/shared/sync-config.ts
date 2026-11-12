export type SyncTransportKind = "webdav" | "github" | "relay";

export interface SyncConfig {
  transport: SyncTransportKind;
  endpoint: string;
  username: string;
  owner: string;
  repo: string;
  branch: string;
  token: string;
}

export const EMPTY_SYNC_CONFIG: SyncConfig = {
  transport: "webdav",
  endpoint: "",
  username: "",
  owner: "",
  repo: "",
  branch: "main",
  token: "",
};

export function validateSyncConfig(config: SyncConfig): string[] {
  const errors: string[] = [];
  if (config.transport === "github") {
    if (!/^[A-Za-z0-9_.-]{1,100}$/.test(config.owner)) errors.push("owner is invalid");
    if (!/^[A-Za-z0-9_.-]{1,100}$/.test(config.repo)) errors.push("repository is invalid");
    if (!/^[A-Za-z0-9_.\/-]{1,200}$/.test(config.branch)) errors.push("branch is invalid");
  } else {
    let url: URL;
    try {
      url = new URL(config.endpoint);
    } catch {
      return ["endpoint must be a valid HTTPS URL"];
    }
    if (url.protocol !== "https:") errors.push("endpoint must use HTTPS");
    if (url.username || url.password) errors.push("credentials must not be embedded in the URL");
    if (url.search) errors.push("endpoint must not contain query parameters");
  }
  if (config.token.length > 0 && config.token.length < 20) {
    errors.push("token is too short");
  }
  return errors;
}

import { GitHubTransport, RelayTransport, WebDavTransport } from "./sync-transport";

export function configForStorage(config: SyncConfig): SyncConfig {
  return {
    ...config,
    endpoint: config.endpoint.trim(),
    username: config.username.trim(),
    owner: config.owner.trim(),
    repo: config.repo.trim(),
    branch: config.branch.trim() || "main",
    token: config.token,
  };
}

/** Test authentication/reachability without uploading data. */
export async function testSyncConnection(config: SyncConfig, vault: string): Promise<string> {
  const errors = validateSyncConfig(config);
  if (errors.length > 0) throw new Error(errors.join("; "));
  if (config.transport === "github") {
    const transport = new GitHubTransport(config.owner, config.repo, config.branch, config.token);
    await transport.pull(vault);
    return "GitHub authenticated request succeeded (no manifest published).";
  }
  const transport =
    config.transport === "relay"
      ? new RelayTransport(config.endpoint, config.token)
      : new WebDavTransport(config.endpoint, config.username, config.token);
  await transport.pull(vault);
  return `${config.transport} authenticated request succeeded (no manifest published).`;
}
