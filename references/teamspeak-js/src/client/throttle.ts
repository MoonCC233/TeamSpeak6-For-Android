interface Waiter {
  readonly resolve: () => void;
  readonly signal: AbortSignal | undefined;
  readonly onAbort: () => void;
}

/**
 * Token-bucket limiter for outbound TS3 commands.
 * Mirrors Go's commandThrottle.
 *
 * Waiters are released in the order they arrived: each used to poll for a
 * token on its own timer, so a command that came later could take the turn of
 * one that had waited longer, and the commands went out of order.
 */
export class CommandThrottle {
  static readonly TOKEN_RATE = 4.0; // tokens per second
  static readonly TOKEN_MAX = 8.0; // bucket capacity

  #tokens = 5.0;
  #lastUpdate = performance.now();
  readonly #waiters: Waiter[] = [];
  #timer: ReturnType<typeof setTimeout> | undefined;

  /**
   * Wait for a token, behind every earlier waiter. Aborting `signal` rejects
   * with its reason and gives the place in line up; the abort listener is
   * removed however the wait ends.
   */
  wait(signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) return Promise.reject(signal.reason);
    this.#refill();
    if (this.#waiters.length === 0 && this.#tokens >= 1.0) {
      this.#tokens -= 1.0;
      return Promise.resolve();
    }
    return new Promise<void>((resolve, reject) => {
      const waiter: Waiter = {
        resolve,
        signal,
        onAbort: () => {
          const index = this.#waiters.indexOf(waiter);
          if (index >= 0) this.#waiters.splice(index, 1);
          if (this.#waiters.length === 0) this.#stopTimer();
          reject(signal!.reason);
        },
      };
      signal?.addEventListener("abort", waiter.onAbort, { once: true });
      this.#waiters.push(waiter);
      this.#schedule();
    });
  }

  #refill(): void {
    const now = performance.now();
    const elapsed = (now - this.#lastUpdate) / 1000;
    this.#tokens = Math.min(
      this.#tokens + elapsed * CommandThrottle.TOKEN_RATE,
      CommandThrottle.TOKEN_MAX,
    );
    this.#lastUpdate = now;
  }

  /** Arm the timer for when the next token is due, unless it already is. */
  #schedule(): void {
    if (this.#timer !== undefined || this.#waiters.length === 0) return;
    const waitMs =
      this.#tokens >= 1.0
        ? 0
        : Math.ceil(((1.0 - this.#tokens) / CommandThrottle.TOKEN_RATE) * 1000) + 10;
    this.#timer = setTimeout(() => {
      this.#timer = undefined;
      this.#release();
    }, waitMs);
  }

  #stopTimer(): void {
    clearTimeout(this.#timer);
    this.#timer = undefined;
  }

  /** Hand the tokens there are to the waiters at the head of the line. */
  #release(): void {
    this.#refill();
    while (this.#waiters.length > 0 && this.#tokens >= 1.0) {
      this.#tokens -= 1.0;
      const waiter = this.#waiters.shift()!;
      waiter.signal?.removeEventListener("abort", waiter.onAbort);
      waiter.resolve();
    }
    this.#schedule();
  }
}
