import { describe, expect, it } from "vitest";
import { ed25519 } from "@noble/curves/ed25519.js";
import { MAX_LICENSE_BLOCKS, parseLicenses } from "./license.js";

const BASE_POINT = ed25519.Point.BASE.toBytes();
const EPHEMERAL_BLOCK_LEN = 42;

/** A version byte and `count` ephemeral blocks, the smallest a block can be. */
function ephemeralChain(count: number): Uint8Array {
  const data = new Uint8Array(1 + count * EPHEMERAL_BLOCK_LEN);
  data[0] = 1;
  for (let i = 0; i < count; i++) {
    const offset = 1 + i * EPHEMERAL_BLOCK_LEN;
    data[offset] = 0; // key kind
    data.set(BASE_POINT, offset + 1);
    data[offset + 33] = 32; // ephemeral
  }
  return data;
}

describe("parseLicenses", () => {
  it(`takes a chain of up to ${MAX_LICENSE_BLOCKS} blocks`, () => {
    const chain = parseLicenses(ephemeralChain(MAX_LICENSE_BLOCKS));
    expect(chain.blocks).toHaveLength(MAX_LICENSE_BLOCKS);
    expect(chain.deriveKey()).toHaveLength(32);
  });

  // Every block costs a point multiplication to derive: a thousand of them
  // held the event loop for seconds.
  it("rejects a longer chain before deriving anything", () => {
    expect(() => parseLicenses(ephemeralChain(MAX_LICENSE_BLOCKS + 1))).toThrow(/longer than/);
    expect(() => parseLicenses(ephemeralChain(1000))).toThrow(/longer than/);
  });

  it("rejects a block that declares more than the license holds", () => {
    // A TS5 server block whose property count, at byte 43, is cut off.
    const ts5 = ephemeralChain(1);
    ts5[34] = 8;
    expect(() => parseLicenses(ts5)).toThrow(/longer than the license/);

    // One whose property claims 200 bytes when 1 follows.
    const property = new Uint8Array(1 + 42 + 4);
    property.set(ephemeralChain(1));
    property[34] = 8;
    property[44] = 1; // one property
    property[45] = 200;
    expect(() => parseLicenses(property)).toThrow(/too short/);
  });
});
