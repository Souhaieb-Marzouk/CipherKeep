import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

const WORKFLOW_DIR = ".github/workflows";
const PIN = /^[^@\s]+@[0-9a-f]{40}$/;

export async function checkWorkflows(dir = WORKFLOW_DIR) {
  const files = (await readdir(dir)).filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"));
  const errors = [];
  for (const file of files) {
    const lines = (await readFile(join(dir, file), "utf8")).split(/\r?\n/);
    lines.forEach((line, index) => {
      const match = line.match(/^\s*uses:\s*([^\s#]+)(?:\s+#\s*(.+))?$/);
      if (!match) {
        return;
      }
      const ref = match[1];
      const comment = match[2] ?? "";
      if (!PIN.test(ref)) {
        errors.push(`${file}:${index + 1}: action is not pinned to a 40-char commit SHA: ${ref}`);
      }
      if (comment.trim().length === 0) {
        errors.push(`${file}:${index + 1}: pinned action needs a version comment: ${ref}`);
      }
      if (/@(main|master|v?\d+(?:\.\d+)*)$/.test(ref)) {
        errors.push(`${file}:${index + 1}: tag/branch reference is forbidden: ${ref}`);
      }
    });
    const source = lines.join("\n");
    if (/permissions:\s*write-all/.test(source)) {
      errors.push(`${file}: write-all permissions are forbidden`);
    }
    if (/pull_request_target/.test(source)) {
      errors.push(`${file}: pull_request_target is forbidden`);
    }
  }
  return errors;
}

if (process.argv.includes("--self-test")) {
  const assert = (condition, message) => {
    if (!condition) {
      throw new Error(message);
    }
  };
  assert(!PIN.test("actions/checkout@v4"), "tag must be rejected");
  assert(PIN.test(`actions/checkout@${"a".repeat(40)}`), "sha must be accepted");
  console.log("action pin self-test: PASS");
} else {
  const errors = await checkWorkflows();
  if (errors.length > 0) {
    console.error(errors.join("\n"));
    process.exit(1);
  }
  console.log("action pin check: PASS");
}
