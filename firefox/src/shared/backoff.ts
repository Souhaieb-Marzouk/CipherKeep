/** Delay policy for failed master-password attempts. */
export const FREE_ATTEMPTS = 5;
export const BASE_DELAY_SECONDS = 30;
export const MAX_DELAY_SECONDS = 3_600;

export function backoffSeconds(failedAttempts: number): number {
  if (failedAttempts < FREE_ATTEMPTS) {
    return 0;
  }
  const exponent = Math.min(Math.max(failedAttempts - FREE_ATTEMPTS, 0), 7);
  return Math.min(BASE_DELAY_SECONDS * 2 ** exponent, MAX_DELAY_SECONDS);
}

export function remainingSeconds(nextAttemptAt: number, nowSeconds: number): number {
  return Math.max(0, nextAttemptAt - nowSeconds);
}
