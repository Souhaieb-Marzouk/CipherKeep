// Cloudflare Email Worker fixture for user-controlled alias catch-all.
//
// HTTP control plane: create/list/disable/delete aliases.
// Email plane: forward only enabled aliases; reject unknown or disabled ones.

export interface Env {
  ALIAS_TOKEN: string;
  ALIASES: KVNamespace;
  DOMAIN: string;
  FORWARD_TO: string;
}

interface AliasRecord {
  address: string;
  provider: "cloudflare";
  providerId: string;
  label: string;
  enabled: boolean;
  createdAt: number;
}

const MAX_LABEL = 128;

function authorized(request: Request, env: Env): boolean {
  if (typeof env.ALIAS_TOKEN !== "string" || env.ALIAS_TOKEN.length < 32) {
    return false;
  }
  const header = request.headers.get("authorization") ?? "";
  const expected = `Bearer ${env.ALIAS_TOKEN}`;
  if (header.length !== expected.length) return false;
  let diff = 0;
  for (let index = 0; index < expected.length; index += 1) {
    diff |= header.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return diff === 0;
}

function aliasKey(address: string): string {
  return `alias:${address.toLowerCase()}`;
}

function slug(label: string): string {
  const value = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 24);
  return value.length === 0 ? "alias" : value;
}

async function list(env: Env): Promise<AliasRecord[]> {
  const records: AliasRecord[] = [];
  let cursor: string | undefined;
  do {
    const page = await env.ALIASES.list({ prefix: "alias:", cursor });
    for (const key of page.keys) {
      const value = await env.ALIASES.get(key.name);
      if (value !== null) records.push(JSON.parse(value) as AliasRecord);
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor !== undefined);
  return records;
}

async function create(request: Request, env: Env): Promise<Response> {
  const body = (await request.json()) as { label?: unknown };
  const label = typeof body.label === "string" ? body.label.trim() : "";
  if (label.length === 0 || label.length > MAX_LABEL) {
    return new Response("invalid label", { status: 400 });
  }
  const providerId = crypto.randomUUID();
  const address = `${slug(label)}-${providerId.slice(0, 8)}@${env.DOMAIN}`;
  const record: AliasRecord = {
    address,
    provider: "cloudflare",
    providerId,
    label,
    enabled: true,
    createdAt: Date.now(),
  };
  await env.ALIASES.put(aliasKey(address), JSON.stringify(record));
  return Response.json(record, { status: 201 });
}

async function update(request: Request, env: Env, address: string, action: string): Promise<Response> {
  const key = aliasKey(address);
  const raw = await env.ALIASES.get(key);
  if (raw === null) return new Response("not found", { status: 404 });
  const record = JSON.parse(raw) as AliasRecord;
  if (action === "disable") {
    record.enabled = false;
    await env.ALIASES.put(key, JSON.stringify(record));
    return Response.json(record);
  }
  if (action === "delete") {
    await env.ALIASES.delete(key);
    return new Response(null, { status: 204 });
  }
  return new Response("not found", { status: 404 });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!authorized(request, env)) {
      return new Response("unauthorized", { status: 401 });
    }
    const url = new URL(request.url);
    if (url.pathname === "/aliases" && request.method === "GET") {
      return Response.json(await list(env), { headers: { "cache-control": "no-store" } });
    }
    if (url.pathname === "/aliases" && request.method === "POST") {
      return create(request, env);
    }
    const match = /^\/aliases\/([^/]+)\/(disable|delete)$/.exec(url.pathname);
    if (match !== null && request.method === "POST") {
      return update(request, env, decodeURIComponent(match[1]), match[2]);
    }
    return new Response("not found", { status: 404 });
  },

  async email(message: EmailMessage, env: Env): Promise<void> {
    const raw = await env.ALIASES.get(aliasKey(message.to));
    if (raw === null) {
      message.setReject("unknown alias");
      return;
    }
    const record = JSON.parse(raw) as AliasRecord;
    if (!record.enabled) {
      message.setReject("alias disabled");
      return;
    }
    await message.forward(env.FORWARD_TO);
  },
};
