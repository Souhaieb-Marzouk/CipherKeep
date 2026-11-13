// Integration test for the Cloudflare Email Worker fixture.
// Uses Node's native TypeScript stripping, a fake KV namespace and a fake
// EmailMessage; no Cloudflare runtime or production data is involved.

import assert from "node:assert/strict";
import worker from "../src/index.ts";

const token = "a".repeat(40);
const env = {
  ALIAS_TOKEN: token,
  DOMAIN: "alias.test",
  FORWARD_TO: "inbox@example.test",
  ALIASES: null,
};

class FakeKv {
  values = new Map();
  async get(key) {
    return this.values.get(key) ?? null;
  }
  async put(key, value) {
    this.values.set(key, value);
  }
  async delete(key) {
    this.values.delete(key);
  }
  async list({ prefix = "", cursor } = {}) {
    const keys = [...this.values.keys()]
      .filter((key) => key.startsWith(prefix))
      .map((name) => ({ name }));
    return { keys, list_complete: true, cursor };
  }
}

env.ALIASES = new FakeKv();

function request(path, init = {}) {
  return new Request(`https://alias.test${path}`, {
    ...init,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(init.headers ?? {}),
    },
  });
}

const unauthorized = await worker.fetch(
  new Request("https://alias.test/aliases"),
  env,
);
assert.equal(unauthorized.status, 401);

const created = await worker.fetch(
  request("/aliases", { method: "POST", body: JSON.stringify({ label: "Example Site" }) }),
  env,
);
assert.equal(created.status, 201);
const alias = await created.json();
assert.match(alias.address, /^example-site-[0-9a-f]{8}@alias\.test$/);
assert.equal(alias.enabled, true);

const listed = await worker.fetch(request("/aliases"), env);
assert.equal(listed.status, 200);
assert.equal((await listed.json()).length, 1);

const disabled = await worker.fetch(
  request(`/aliases/${encodeURIComponent(alias.address)}/disable`, { method: "POST" }),
  env,
);
assert.equal(disabled.status, 200);

let rejected = "";
const disabledMessage = {
  to: alias.address,
  setReject(reason) {
    rejected = reason;
  },
  async forward() {
    throw new Error("disabled alias must not forward");
  },
};
await worker.email(disabledMessage, env);
assert.equal(rejected, "alias disabled");

rejected = "";
const unknownMessage = {
  to: "unknown@alias.test",
  setReject(reason) {
    rejected = reason;
  },
  async forward() {},
};
await worker.email(unknownMessage, env);
assert.equal(rejected, "unknown alias");

const enabledAlias = await worker.fetch(
  request("/aliases", { method: "POST", body: JSON.stringify({ label: "Forward Me" }) }),
  env,
).then((response) => response.json());
let forwardedTo = "";
const message = {
  to: enabledAlias.address,
  setReject() {
    throw new Error("enabled alias must not reject");
  },
  async forward(to) {
    forwardedTo = to;
  },
};
await worker.email(message, env);
assert.equal(forwardedTo, env.FORWARD_TO);

const removed = await worker.fetch(
  request(`/aliases/${encodeURIComponent(enabledAlias.address)}/delete`, { method: "POST" }),
  env,
);
assert.equal(removed.status, 204);

console.log("email-alias Worker integration: PASS");
