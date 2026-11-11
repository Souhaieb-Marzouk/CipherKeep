/** Shared timeout policy for the Firefox background alarm and tests. */
export class AutoLock {
  #lastActivity: number;
  #locked = false;

  constructor(
    readonly timeoutMs = 60_000,
    now = 0,
  ) {
    this.#lastActivity = now;
  }

  get locked(): boolean {
    return this.#locked;
  }

  recordActivity(now: number): void {
    this.#lastActivity = now;
  }

  shouldLock(now: number): boolean {
    return !this.#locked && now - this.#lastActivity >= this.timeoutMs;
  }

  remaining(now: number): number {
    return Math.max(0, this.timeoutMs - (now - this.#lastActivity));
  }

  lock(): void {
    this.#locked = true;
  }
}
