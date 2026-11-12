export interface Recipe {
  origin: string;
  usernameSelector: string;
  passwordSelector: string;
  submitSelector?: string;
}

export interface RecipeRegistry {
  version: number;
  recipes: Recipe[];
}

export interface RecipePublicKey {
  kty: "EC";
  crv: "P-256";
  x: string;
  y: string;
}

/** Canonical JSON: recursively sorted keys, no whitespace. */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(object[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function validOrigin(origin: string): boolean {
  return (
    origin.length > 0 &&
    origin.length <= 255 &&
    !/[\s/:@?#]/.test(origin)
  );
}

function validSelector(selector: string): boolean {
  return (
    selector.length > 0 &&
    selector.length <= 512 &&
    !/[\u0000-\u001f]/.test(selector) &&
    !selector.toLowerCase().includes("javascript:") &&
    !selector.includes("innerHTML")
  );
}

export function parseRegistry(value: unknown): RecipeRegistry {
  if (value === null || typeof value !== "object") {
    throw new Error("registry must be an object");
  }
  const raw = value as Record<string, unknown>;
  const version = raw.version;
  if (typeof version !== "number" || !Number.isInteger(version) || version <= 0) {
    throw new Error("invalid registry version");
  }
  if (!Array.isArray(raw.recipes) || raw.recipes.length > 1_000) {
    throw new Error("invalid recipe list");
  }
  const recipes: Recipe[] = raw.recipes.map((entry) => {
    if (entry === null || typeof entry !== "object") {
      throw new Error("invalid recipe");
    }
    const recipe = entry as Record<string, unknown>;
    const origin = recipe.origin;
    const usernameSelector = recipe.usernameSelector;
    const passwordSelector = recipe.passwordSelector;
    const submitSelector = recipe.submitSelector;
    if (
      typeof origin !== "string" ||
      !validOrigin(origin) ||
      typeof usernameSelector !== "string" ||
      typeof passwordSelector !== "string" ||
      !validSelector(usernameSelector) ||
      !validSelector(passwordSelector) ||
      (submitSelector !== undefined &&
        (typeof submitSelector !== "string" || !validSelector(submitSelector)))
    ) {
      throw new Error("invalid recipe fields");
    }
    return {
      origin: origin.toLowerCase(),
      usernameSelector,
      passwordSelector,
      ...(submitSelector === undefined ? {} : { submitSelector }),
    };
  });
  return { version, recipes };
}

/** Verify a canonical-JSON registry with ECDSA P-256/SHA-256. */
export async function verifyRegistry(
  json: string,
  signatureBase64: string,
  publicKey: RecipePublicKey,
): Promise<RecipeRegistry> {
  const parsed = parseRegistry(JSON.parse(json));
  const canonical = new TextEncoder().encode(canonicalJson(parsed));
  const signature = Uint8Array.from(atob(signatureBase64), (character) =>
    character.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey(
    "jwk",
    publicKey,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    signature,
    canonical,
  );
  if (!valid) {
    throw new Error("invalid registry signature");
  }
  return parsed;
}

export function recipeForOrigin(
  registry: RecipeRegistry,
  origin: string,
): Recipe | undefined {
  return registry.recipes.find(
    (recipe) => recipe.origin.toLowerCase() === origin.toLowerCase(),
  );
}
