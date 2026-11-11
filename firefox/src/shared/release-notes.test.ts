import { describe, expect, it } from "vitest";
import { sanitizeReleaseNotes } from "./release-notes";

describe("release notes", () => {
  it("strips scripts and tags", () => {
    const notes = sanitizeReleaseNotes(
      "<h2>Changes</h2><script>alert('x')</script><p>Fixed <b>lock</b> flow.</p>",
    );
    expect(notes).not.toContain("<script>");
    expect(notes).not.toContain("<h2>");
    expect(notes).toContain("Changes");
    expect(notes).toContain("Fixed lock flow.");
  });

  it("keeps only allowlisted links", () => {
    const notes = sanitizeReleaseNotes(
      '<a href="https://github.com/Souhaieb-Marzouk/CipherKeep/releases">Release</a> ' +
        '<a href="https://evil.example/x">Bad</a>',
    );
    expect(notes).toContain("Release (https://github.com/Souhaieb-Marzouk/CipherKeep/releases)");
    expect(notes).not.toContain("evil.example");
  });
});
