export interface Env {
  TOKEN: string;
  BUCKET: R2Bucket;
}

const MAX_BYTES = 64 * 1024 * 1024;

function authorized(request: Request, env: Env): boolean {
  if (typeof env.TOKEN !== "string" || env.TOKEN.length < 32) return false;
  const header = request.headers.get("authorization") ?? "";
  const expected = `Bearer ${env.TOKEN}`;
  if (header.length !== expected.length) return false;
  let diff = 0;
  for (let index = 0; index < expected.length; index += 1) {
    diff |= header.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return diff === 0;
}

function keyFor(url: URL): string | null {
  const match = /^\/v1\/([a-f0-9]{32})\/(manifest|blobs\/[a-f0-9]{32}-\d+)$/.exec(url.pathname);
  return match === null ? null : `${match[1]}/${match[2]}`;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!authorized(request, env)) {
      return new Response("unauthorized", { status: 401 });
    }
    const url = new URL(request.url);
    const key = keyFor(url);
    if (key === null) {
      return new Response("not found", { status: 404 });
    }
    if (request.method === "GET" || request.method === "HEAD") {
      const object = await env.BUCKET.get(key);
      if (object === null) {
        return new Response("not found", { status: 404 });
      }
      return new Response(request.method === "HEAD" ? null : object.body, {
        headers: {
          "content-type": "application/octet-stream",
          etag: object.httpEtag,
          "cache-control": "no-store",
        },
      });
    }
    if (request.method === "PUT") {
      const length = Number(request.headers.get("content-length") ?? "0");
      if (length <= 0 || length > MAX_BYTES) {
        return new Response("invalid size", { status: 413 });
      }
      const ifMatch = request.headers.get("if-match");
      if (ifMatch !== null) {
        const current = await env.BUCKET.head(key);
        if (current === null || current.httpEtag !== ifMatch) {
          return new Response("precondition failed", { status: 412 });
        }
      }
      const body = await request.arrayBuffer();
      if (body.byteLength > MAX_BYTES) {
        return new Response("invalid size", { status: 413 });
      }
      const object = await env.BUCKET.put(key, body, {
        httpMetadata: { contentType: "application/octet-stream" },
      });
      return new Response(null, {
        status: 201,
        headers: { etag: object.httpEtag, "cache-control": "no-store" },
      });
    }
    if (request.method === "DELETE") {
      await env.BUCKET.delete(key);
      return new Response(null, { status: 204 });
    }
    return new Response("method not allowed", { status: 405 });
  },
};
