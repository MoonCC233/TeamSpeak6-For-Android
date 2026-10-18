import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { ed25519 } from "@noble/curves/ed25519.js";
import { MAX_LICENSE_BLOCKS, parseLicenses } from "./license.js";

// The license chain is the server's, sent before the handshake has proved
// anything: any bytes can arrive in it. Reading it must end in a chain of a
// bounded number of blocks or in an Error, and deriving a key from a chain
// that read must give a key or an Error, never anything else.

const BASE_POINT = ed25519.Point.BASE.toBytes();

const key = fc.oneof(fc.constant(BASE_POINT), fc.uint8Array({ minLength: 32, maxLength: 32 }));
const times = fc
  .tuple(fc.nat(0xffff_ffff), fc.nat(0xffff_ffff))
  .map(([a, b]): [number, number] => [Math.min(a, b), Math.max(a, b)]);
const issuer = fc.uint8Array({ maxLength: 12 }).map((bytes) => bytes.map((b) => b || 1));

/** One block, laid out as the parser reads its type, with a length that fits it. */
const block = fc
  .tuple(
    key,
    times,
    fc.constantFrom(0, 2, 8, 32, 7),
    fc.uint8Array({ minLength: 6, maxLength: 6 }),
    issuer,
    fc.array(fc.uint8Array({ maxLength: 6 }), { maxLength: 4 }),
  )
  .map(([point, [before, after], type, fixed, name, properties]) => {
    const head = new Uint8Array(42);
    head.set(point, 1);
    head[33] = type;
    new DataView(head.buffer).setUint32(34, before, false);
    new DataView(head.buffer).setUint32(38, after, false);
    const text = Uint8Array.from([...name, 0]);
    switch (type) {
      case 0:
        return Uint8Array.from([...head, ...fixed.subarray(0, 4), ...text]);
      case 2:
        return Uint8Array.from([...head, ...fixed.subarray(0, 5), ...text]);
      case 8:
        return Uint8Array.from([
          ...head,
          fixed[0]!,
          properties.length,
          ...properties.flatMap((p) => [p.length, ...p]),
        ]);
      case 7:
      case 32:
      default:
        return head;
    }
  });

const chain = fc
  .tuple(fc.constantFrom(1, 1, 1, 0, 2), fc.array(block, { maxLength: MAX_LICENSE_BLOCKS + 1 }))
  .map(([version, blocks]) => Uint8Array.from([version, ...blocks.flatMap((b) => [...b])]));

/** A well-formed chain with some bytes changed or cut off. */
const damaged = fc
  .tuple(chain, fc.array(fc.tuple(fc.nat(400), fc.nat(255)), { maxLength: 4 }), fc.nat(400))
  .map(([data, edits, cut]) => {
    const out = Uint8Array.from(data);
    for (const [at, byte] of edits) if (at < out.length) out[at] = byte;
    return cut < out.length && cut % 3 === 0 ? out.subarray(0, cut) : out;
  });

describe("a license chain from the server", () => {
  it("reads to a bounded chain or an Error, and derives a key or an Error", () => {
    fc.assert(
      fc.property(fc.oneof(chain, damaged, fc.uint8Array({ maxLength: 200 })), (data) => {
        let parsed;
        try {
          parsed = parseLicenses(data);
        } catch (error) {
          expect(error).toBeInstanceOf(Error);
          return;
        }
        expect(parsed.blocks.length).toBeLessThanOrEqual(MAX_LICENSE_BLOCKS);
        try {
          expect(parsed.deriveKey()).toHaveLength(32);
        } catch (error) {
          expect(error).toBeInstanceOf(Error);
        }
      }),
      { numRuns: 300 },
    );
  });
});
