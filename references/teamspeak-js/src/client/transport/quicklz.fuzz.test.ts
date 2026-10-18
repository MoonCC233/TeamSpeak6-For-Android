import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { Qlz } from "./quicklz.js";

// QuickLZ input arrives in packets anyone can send before the handshake, so
// decompressing it may fail only with QuickLZ's own error, and never
// produces more than the cap.
describe("QuickLZ", () => {
  it("rejects arbitrary input cleanly, and never inflates past 1 MiB", () => {
    fc.assert(
      fc.property(fc.uint8Array({ maxLength: 2048 }), (data) => {
        try {
          expect(new Qlz().decompress(data).length).toBeLessThanOrEqual(1 << 20);
        } catch (error) {
          expect(error).toBeInstanceOf(Error);
          expect(String(error)).toMatch(/QuickLZ/);
        }
      }),
      { numRuns: 5000 },
    );
  });
});
