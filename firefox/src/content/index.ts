/**
 * Content-script surface reserved for P1 autofill.
 *
 * This file is bundled so the extension is XPI-ready; this phase does not
 * register a content script: site access is optional and no host permission is
 * held at install. P1 will inject this only after a user gesture on an origin
 * that has been granted explicitly.
 */
export function loginFields(root: ParentNode = document): HTMLInputElement[] {
  return Array.from(root.querySelectorAll<HTMLInputElement>('input[type="password"]'));
}

export function hasLoginForm(root: ParentNode = document): boolean {
  return loginFields(root).length > 0;
}
