import { afterEach, describe, it, expect, vi } from "vitest";
import { CommandThrottle } from "./throttle.js";

describe("CommandThrottle", () => {
  afterEach(() => vi.useRealTimers());

  it("allows immediate calls when tokens are available", async () => {
    vi.useFakeTimers();
    const t = new CommandThrottle();
    const start = performance.now();
    // 5 calls succeed without the clock moving (initial tokens = 5)
    for (let i = 0; i < 5; i++) await t.wait();
    expect(performance.now() - start).toBe(0);
  });

  it("throttles when bucket is exhausted", async () => {
    vi.useFakeTimers();
    const t = new CommandThrottle();
    // Exhaust initial 5 tokens
    for (let i = 0; i < 5; i++) await t.wait();
    let released = false;
    void t.wait().then(() => (released = true));

    // One more token takes 250 ms at rate 4/s.
    await vi.advanceTimersByTimeAsync(200);
    expect(released).toBe(false);
    await vi.advanceTimersByTimeAsync(100);
    expect(released).toBe(true);
  });

  it("respects AbortSignal", async () => {
    vi.useFakeTimers();
    const t = new CommandThrottle();
    // Exhaust tokens
    for (let i = 0; i < 5; i++) await t.wait();

    const controller = new AbortController();
    setTimeout(() => controller.abort(new Error("aborted")), 50);

    const waiting = expect(t.wait(controller.signal)).rejects.toThrow("aborted");
    await vi.advanceTimersByTimeAsync(50);
    await waiting;
  });

  // Each waiter used to poll on its own timer, so a later one could take the
  // turn of one that had waited longer.
  it("releases waiters in the order they arrived", async () => {
    vi.useFakeTimers();
    try {
      const t = new CommandThrottle();
      const order: number[] = [];
      const waits: Array<Promise<void>> = [];
      for (let i = 0; i < 12; i++) waits.push(t.wait().then(() => void order.push(i)));
      // A token is due at 250 ms, and the sleepers woke 10 ms after it.
      await vi.advanceTimersByTimeAsync(255);
      for (let i = 12; i < 14; i++) waits.push(t.wait().then(() => void order.push(i)));
      const aborted = new AbortController();
      const givenUp = expect(t.wait(aborted.signal)).rejects.toThrow("gave up");
      aborted.abort(new Error("gave up"));
      waits.push(t.wait().then(() => void order.push(14)));
      await vi.advanceTimersByTimeAsync(10_000);
      await Promise.all(waits);

      expect(order).toEqual([...Array(15).keys()]);
      await givenUp;
    } finally {
      vi.useRealTimers();
    }
  });
});
