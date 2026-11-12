import type { RecipePublicKey } from "./recipes";

/** Pinned publisher key for the offline recipe registry. Rotation requires an extension release. */
export const RECIPE_PUBLIC_KEY: RecipePublicKey = {
  kty: "EC",
  crv: "P-256",
  x: "vW4bWI0zqIKXUqfuPknmHR9KQkfNYMjOdL61UIHjERY",
  y: "mzVUW-M9eJ3qJZ_wgW9e_Vr9dOSNokWkz4mXgmtfreg",
} as const;
