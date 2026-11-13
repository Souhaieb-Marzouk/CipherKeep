// User-deployed emergency cron fixture.
//
// Stores only timestamps, contact addresses and opaque encrypted share
// envelopes. It cannot decrypt a vault and never receives a master key.

export interface Env {
  EMERGENCY_TOKEN: string;
  POLICIES: KVNamespace;
  NOTIFY_WEBHOOK: string;
  NOTIFY_TOKEN: string;
}

interface Contact {
  id: string;
  address: string;
}

interface Share {
  contactId: string;
  ciphertext: string;
}

interface Policy {
  id: string;
  state: "armed" | "cancelled" | "released";
  lastCheckIn: number;
  inactivitySecs: number;
  reminderLeadSecs: number;
  graceSecs: number;
  reminderSent: boolean;
  contacts: Contact[];
  shares: Share[];
}

const MAX_CONTACTS = 2;
const MAX_BODY = 64 * 1024;

function authorized(request: Request, env: Env): boolean {
  if (typeof env.EMERGENCY_TOKEN !== "string" || env.EMERGENCY_TOKEN.length < 32) {
    return false;
  }
  const header = request.headers.get("authorization") ?? "";
  const expected = `Bearer ${env.EMERGENCY_TOKEN}`;
  if (header.length !== expected.length) return false;
  let diff = 0;
  for (let index = 0; index < expected.length; index += 1) {
    diff |= header.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return diff === 0;
}

function key(id: string): string {
  return `policy:${id}`;
}

function deadline(policy: Policy): number {
  return policy.lastCheckIn + policy.inactivitySecs + policy.graceSecs;
}

function reminderAt(policy: Policy): number {
  return policy.lastCheckIn + policy.inactivitySecs - policy.reminderLeadSecs;
}

async function body(request: Request): Promise<unknown> {
  const text = await request.text();
  if (text.length > MAX_BODY) throw new Error("body too large");
  return JSON.parse(text) as unknown;
}

function parsePolicy(value: unknown, id: string): Policy {
  if (typeof value !== "object" || value === null) throw new Error("invalid policy");
  const raw = value as Record<string, unknown>;
  if (
    !Array.isArray(raw.contacts) ||
    raw.contacts.length === 0 ||
    raw.contacts.length > MAX_CONTACTS ||
    !Array.isArray(raw.shares) ||
    typeof raw.inactivitySecs !== "number" ||
    typeof raw.reminderLeadSecs !== "number" ||
    typeof raw.graceSecs !== "number"
  ) {
    throw new Error("invalid policy");
  }
  const contacts = raw.contacts as Contact[];
  const shares = raw.shares as Share[];
  if (
    contacts.some((contact) => !contact.id || !contact.address) ||
    shares.length !== contacts.length ||
    shares.some((share) => !share.contactId || !share.ciphertext)
  ) {
    throw new Error("invalid policy");
  }
  return {
    id,
    state: "armed",
    lastCheckIn: Math.floor(Date.now() / 1000),
    inactivitySecs: raw.inactivitySecs,
    reminderLeadSecs: raw.reminderLeadSecs,
    graceSecs: raw.graceSecs,
    reminderSent: false,
    contacts,
    shares,
  };
}

async function notify(env: Env, payload: unknown): Promise<void> {
  if (env.NOTIFY_WEBHOOK.length === 0) return;
  await fetch(env.NOTIFY_WEBHOOK, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.NOTIFY_TOKEN}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(payload),
    redirect: "error",
  });
}

async function evaluate(policy: Policy, env: Env, now: number): Promise<Policy> {
  if (policy.state !== "armed") return policy;
  if (now >= deadline(policy)) {
    for (const share of policy.shares) {
      const contact = policy.contacts.find((entry) => entry.id === share.contactId);
      if (contact === undefined) continue;
      await notify(env, {
        kind: "release",
        to: contact.address,
        policyId: policy.id,
        share: share.ciphertext,
      });
    }
    return { ...policy, state: "released" };
  }
  if (now >= reminderAt(policy) && !policy.reminderSent) {
    await notify(env, {
      kind: "reminder",
      to: policy.contacts.map((contact) => contact.address),
      policyId: policy.id,
      deadline: deadline(policy),
    });
    return { ...policy, reminderSent: true };
  }
  return policy;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!authorized(request, env)) return new Response("unauthorized", { status: 401 });
    const url = new URL(request.url);
    if (url.pathname === "/policies" && request.method === "POST") {
      const id = crypto.randomUUID();
      const policy = parsePolicy(await body(request), id);
      await env.POLICIES.put(key(id), JSON.stringify(policy));
      return Response.json(policy, { status: 201, headers: { "cache-control": "no-store" } });
    }
    const match = /^\/policies\/([0-9a-f-]{36})(\/checkin|\/cancel)?$/.exec(url.pathname);
    if (match === null) return new Response("not found", { status: 404 });
    const raw = await env.POLICIES.get(key(match[1]));
    if (raw === null) return new Response("not found", { status: 404 });
    let policy = JSON.parse(raw) as Policy;
    if (match[2] === "/checkin" && request.method === "POST") {
      if (policy.state !== "armed") return new Response("not armed", { status: 409 });
      policy.lastCheckIn = Math.floor(Date.now() / 1000);
      policy.reminderSent = false;
    } else if (match[2] === "/cancel" && request.method === "POST") {
      policy.state = "cancelled";
    } else if (match[2] === undefined && request.method === "GET") {
      // Read-only status.
    } else {
      return new Response("not found", { status: 404 });
    }
    await env.POLICIES.put(key(policy.id), JSON.stringify(policy));
    return Response.json(policy, { headers: { "cache-control": "no-store" } });
  },

  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    const now = Math.floor(controller.scheduledTime / 1000);
    let cursor: string | undefined;
    do {
      const page = await env.POLICIES.list({ prefix: "policy:", cursor });
      for (const entry of page.keys) {
        const raw = await env.POLICIES.get(entry.name);
        if (raw === null) continue;
        const policy = JSON.parse(raw) as Policy;
        const next = await evaluate(policy, env, now);
        if (next !== policy) await env.POLICIES.put(entry.name, JSON.stringify(next));
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor !== undefined);
  },
};
