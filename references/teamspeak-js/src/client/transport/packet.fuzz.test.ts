import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { GenerationWindow } from "./generation-window.js";
import {
  buildC2SHeader,
  packetFlags,
  packetType,
  parseC2SHeader,
  parseS2CHeader,
} from "./packet.js";
import type { Packet } from "./packet.js";

// Packet ids are 16 bits and wrap; the window places each received id in its
// generation, which decides the key it is decrypted under, whether it is
// acknowledged, and whether it waits for a packet before it. A server sends
// the ids, so it chooses where in the wrap the window sits.

const MOD = 1 << 16;
const WINDOW = 1024;

/** The absolute position a window has been advanced to, which the model tracks. */
interface Placed {
  readonly window: GenerationWindow;
  readonly absolute: number;
}

/** A window advanced by each step in turn, with the absolute position that makes. */
function advancedBy(steps: readonly number[]): Placed {
  const window = new GenerationWindow(MOD, WINDOW);
  let absolute = 0;
  for (const step of steps) {
    window.advance(step);
    absolute += step;
  }
  return { window, absolute };
}

const steps = fc.array(
  fc.oneof(
    fc.integer({ min: 0, max: 3 }),
    fc.integer({ min: 0, max: 70_000 }),
    fc.constantFrom(MOD - 1, MOD, MOD + 1, MOD - WINDOW, MOD - WINDOW + 1, 1023, 1024),
  ),
  { maxLength: 12 },
);

describe("the receive window over the 16-bit wrap", () => {
  it("counts the generation as the number of wraps", () => {
    fc.assert(
      fc.property(steps, (list) => {
        const { window, absolute } = advancedBy(list);
        expect(window.generation).toBe(Math.floor(absolute / MOD));
      }),
      { numRuns: 1000 },
    );
  });

  it("says in the window what is neither old nor ahead of it", () => {
    fc.assert(
      fc.property(steps, fc.integer({ min: 0, max: MOD - 1 }), (list, id) => {
        const { window } = advancedBy(list);
        expect(window.isInWindow(id)).toBe(!window.isOldPacket(id) && !window.isFuturePacket(id));
      }),
      { numRuns: 2000 },
    );
  });

  it("puts each id in the window at its absolute position, and so in its generation", () => {
    fc.assert(
      fc.property(steps, fc.integer({ min: 0, max: WINDOW - 1 }), (list, offset) => {
        const { window, absolute } = advancedBy(list);
        const id = (absolute + offset) % MOD;
        expect(window.isInWindow(id)).toBe(true);
        expect(window.mappedToIndex(id)).toBe(offset);
        expect(window.getGeneration(id)).toBe(Math.floor((absolute + offset) / MOD));
      }),
      { numRuns: 2000 },
    );
  });

  it("takes the ids just behind the window as old, away from the wrap", () => {
    // A packet whose acknowledgement was lost is sent again after the window
    // has moved past it; an old packet is acknowledged again, anything else
    // outside the window is dropped unanswered, and the server goes on sending it.
    fc.assert(
      fc.property(steps, fc.integer({ min: 1, max: WINDOW }), (list, behind) => {
        const { window, absolute } = advancedBy(list);
        fc.pre(absolute % MOD >= behind);
        const id = (absolute % MOD) - behind;
        expect(window.isInWindow(id)).toBe(false);
        expect(window.isOldPacket(id), `${behind} behind absolute ${absolute}`).toBe(true);
      }),
      { numRuns: 2000 },
    );
  });

  // Just after the wrap, the ids the window has left behind are at the top of
  // the 16-bit range: a retransmission of one must still read as old, and
  // belong to the generation before the window's, or it is dropped without the
  // acknowledgement the server is waiting for.
  it("takes the ids just behind the window as old, across the wrap", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: WINDOW - 1 }),
        fc.integer({ min: 1, max: WINDOW }),
        (past, behind) => {
          const { window } = advancedBy([MOD + past]);
          fc.pre(behind > past);
          const id = (past - behind + MOD) % MOD;
          expect(window.isOldPacket(id), `id ${id}, window at ${past} of generation 1`).toBe(true);
          expect(window.isFuturePacket(id)).toBe(false);
          expect(window.isInWindow(id)).toBe(false);
          expect(window.getGeneration(id)).toBe(0);
        },
      ),
      { numRuns: 1000 },
    );
  });
});

describe("packet headers", () => {
  it("read back the header written for them", () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 0xffff }),
        fc.integer({ min: 0, max: 0xffff }),
        fc.integer({ min: 0, max: 0xff }),
        (id, clientID, typeFlagged) => {
          const packet: Packet = {
            typeFlagged,
            id,
            clientID,
            generationID: 0,
            data: new Uint8Array(0),
            receivedAt: 0,
          };
          expect(parseC2SHeader(buildC2SHeader(packet))).toEqual({ id, clientID, typeFlagged });
          expect(packetType(packet) | packetFlags(packet)).toBe(typeFlagged);
        },
      ),
      { numRuns: 1000 },
    );
  });

  it("read any three bytes as a server header, and take no other length", () => {
    fc.assert(
      fc.property(fc.uint8Array({ minLength: 3, maxLength: 40 }), (raw) => {
        const header = parseS2CHeader(raw);
        expect(header.id).toBe((raw[0]! << 8) | raw[1]!);
        expect(header.typeFlagged).toBe(raw[2]);
      }),
      { numRuns: 1000 },
    );
  });
});
