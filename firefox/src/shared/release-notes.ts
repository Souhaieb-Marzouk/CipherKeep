const ALLOWED_PREFIX = "https://github.com/Souhaieb-Marzouk/CipherKeep";
const MAX_LENGTH = 20_000;

/** Convert untrusted release markdown/HTML into plain display text. */
export function sanitizeReleaseNotes(raw: string): string {
  let text = raw
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "");

  text = text.replace(
    /<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)<\/a>/gi,
    (_match, url: string, label: string) => {
      const cleanLabel = label.replace(/<[^>]+>/g, "");
      return url.startsWith(ALLOWED_PREFIX) ? `${cleanLabel} (${url})` : cleanLabel;
    },
  );
  text = text.replace(/<[^>]+>/g, "");
  text = text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
  return text.trim().slice(0, MAX_LENGTH);
}
