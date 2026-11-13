// Dependency-free integration test for the emergency cron fixture.
// Fake KV, fake notifier and a controlled scheduledTime; no production data.

import assert from "node:assert/strict";
import worker from "../src/index.ts";

const token = "b".repeat(40);
class FakeKv {
  values = new Map();
  async get(key) { return this.values.get(key) ?? null; }
  async put(key, value) { this.values.set(key, value); }
  async list({ prefix = "", cursor } = {}) {
    return {
      keys: [...this.values.keys()].filter((key) => key.startsWith(prefix)).map((name) => ({ name })),
      list_complete: true,
      cursor,
    };
  }
}
const env = {
  EMERGENCY_TOKEN: token,
  POLICIES: new FakeKv(),
  NOTIFY_WEBHOOK: "https://notify.test/hook",
  NOTIFY_TOKEN: "n".repeat(40),
};
const notifications = [];
globalThis.fetch = async (_url, init) => {
  notifications.push(JSON.parse(init.body));
  return new Response(null, { status: 204 });
};
function request(path, init = {}) {
  return new Request(`https://emergency.test${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

assert.equal((await worker.fetch(new Request("https://emergency.test/policies"), env)).status, 401);
const created = await worker.fetch(
  request("/policies", {
    method: "POST",
    body: JSON.stringify({
      inactivitySecs: 100,
      reminderLeadSecs: 10,
      graceSecs: 10,
      contacts: [{ id: "c1", address: "alice@example.test" }],
      shares: [{ contactId: "c1", ciphertext: "b3BhcXVl" }],
    }),
  }),
  env,
);
assert.equal(created.status, 201);
const policy = await created.json();
assert.equal(policy.state, "armed");

const now = Math.floor(Date.now() / 1000);
await worker.scheduled({ scheduledTime: (now + 95) * 1000 }, env);
assert.equal(notifications.at(-1).kind, "reminder");
assert.equal(notifications.at(-1).to[0], "alice@example.test");

await worker.scheduled({ scheduledTime: (now + 115) * 1000 }, env);
assert.equal(notifications.at(-1).kind, "release");
assert.equal(notifications.at(-1).share, "b3BhcXVl");
assert.ok(!("key" in notifications.at(-1)));

const status = await worker.fetch(request(`/policies/${policy.id}`), env);
assert.equal((await status.json()).state, "released");

const second = await worker.fetch(
  request("/policies", {
    method: "POST",
    body: JSON.stringify({
      inactivitySecs: 100,
      reminderLeadSecs: 10,
      graceSecs: 10,
      contacts: [{ id: "c1", address: "alice@example.test" }],
      shares: [{ contactId: "c1", ciphertext: "b3BhcXVl" }],
    }),
  }),
  env,
).then((response) => response.json());
const cancelled = await worker.fetch(request(`/policies/${second.id}/cancel`, { method: "POST" }), env);
assert.equal((await cancelled.json()).state, "cancelled");
await worker.scheduled({ scheduledTime: (now + 10_000) * 1000 }, env);
assert.notEqual(notifications.at(-1)?.policyId, second.id);

console.log("emergency cron integration: PASS");
