import { sanitizeReleaseNotes } from "../shared/release-notes";
import { isNewer, parseVersion } from "../shared/version";

export const LATEST_RELEASE =
  "https://api.github.com/repos/Souhaieb-Marzouk/CipherKeep/releases/latest";

export type UpdateReport =
  | {
      kind: "available";
      currentVersion: string;
      latestVersion: string;
      publishedAt: string;
      sizeBytes: number;
      notes: string;
      releaseUrl: string;
    }
  | { kind: "up-to-date"; currentVersion: string; latestVersion: string }
  | { kind: "rate-limited" }
  | { kind: "error"; message: string };

export async function checkForUpdates(
  fetchImpl: typeof fetch = fetch,
  currentVersion = browser.runtime.getManifest().version,
): Promise<UpdateReport> {
  const stored = await browser.storage.local.get(["updateEtag", "updateBody"]);
  const etag = typeof stored.updateEtag === "string" ? stored.updateEtag : null;
  const cached = typeof stored.updateBody === "string" ? stored.updateBody : null;

  try {
    const headers: Record<string, string> = {
      Accept: "application/vnd.github+json",
      // Browsers treat User-Agent as a forbidden header. This client header is
      // the explicit CipherKeep identifier available to extension fetch.
      "X-CipherKeep-Client": `CipherKeep-Firefox/${currentVersion}`,
    };
    if (etag !== null) {
      headers["If-None-Match"] = etag;
    }
    const response = await fetchImpl(LATEST_RELEASE, { headers, cache: "no-store" });
    if (response.status === 304) {
      if (cached === null) {
        return { kind: "error", message: "No cached release metadata." };
      }
      return parseRelease(cached, currentVersion);
    }
    if (response.status === 403 || response.status === 429) {
      return { kind: "rate-limited" };
    }
    if (!response.ok) {
      return { kind: "error", message: `GitHub returned HTTP ${response.status}.` };
    }
    const body = await response.text();
    const nextEtag = response.headers.get("ETag");
    if (nextEtag !== null) {
      await browser.storage.local.set({ updateEtag: nextEtag, updateBody: body });
    }
    return parseRelease(body, currentVersion);
  } catch {
    return { kind: "error", message: "Could not reach GitHub. Try again later." };
  }
}

function parseRelease(raw: string, currentVersion: string): UpdateReport {
  try {
    const json = JSON.parse(raw) as {
      tag_name?: unknown;
      published_at?: unknown;
      body?: unknown;
      html_url?: unknown;
      assets?: Array<{ size?: unknown }>;
    };
    const tag = typeof json.tag_name === "string" ? json.tag_name : "";
    const latest = parseVersion(tag);
    const current = parseVersion(currentVersion);
    if (!latest || !current) {
      return { kind: "error", message: "The release tag is not a valid version." };
    }
    const sizeBytes = Array.isArray(json.assets)
      ? json.assets.reduce((sum, asset) => sum + (typeof asset.size === "number" ? asset.size : 0), 0)
      : 0;
    if (isNewer(currentVersion, tag)) {
      return {
        kind: "available",
        currentVersion,
        latestVersion: tag,
        publishedAt: typeof json.published_at === "string" ? json.published_at : "",
        sizeBytes,
        notes: sanitizeReleaseNotes(typeof json.body === "string" ? json.body : ""),
        releaseUrl: typeof json.html_url === "string" ? json.html_url : "",
      };
    }
    return { kind: "up-to-date", currentVersion, latestVersion: tag };
  } catch {
    return { kind: "error", message: "GitHub returned malformed release metadata." };
  }
}
