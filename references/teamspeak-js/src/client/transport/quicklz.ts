const TABLE_SIZE = 4096;

/**
 * The largest decompressed command accepted. A command is reassembled from at
 * most a receive window of fragments, and the largest real answers (a full
 * permission list, the welcome channel list of a big server) are a few
 * hundred kilobytes; a header declaring more is refused before anything is
 * allocated.
 */
export const MAX_DECOMPRESSED_SIZE: number = 1 << 20;

/**
 * How much larger than its compressed bytes a level-1 block can be. The most
 * a 3-byte back-reference yields is 255 bytes, and every 32 entries also cost
 * a 4-byte control word, which bounds the ratio at about 82; a block declaring
 * more cannot be honest, and is refused before its output is allocated.
 */
const MAX_EXPANSION = 128;

interface QlzState {
  control: number;
  sourcePos: number;
  destPos: number;
  nextHashed: number;
}

/** A QuickLZ block that cannot be decompressed: truncated, oversized or inconsistent. */
export class QuickLZError extends Error {
  constructor(message: string) {
    super(`QuickLZ: ${message}`);
    this.name = "QuickLZError";
  }
}

/**
 * QuickLZ level 1 decompression, as the server compresses large commands.
 *
 * The input comes from the network, so every read and every back-reference is
 * checked, and malformed input throws a QuickLZError rather than reading out
 * of range or producing a partly filled buffer.
 */
export class Qlz {
  #hashtable = new Int32Array(TABLE_SIZE);

  decompress(data: Uint8Array): Uint8Array {
    const { headerLen, decompressedSize, flags } = parseQlzHeader(data);

    if ((flags & 0x01) === 0) {
      if (headerLen + decompressedSize > data.length) {
        throw new QuickLZError("stored block is shorter than its declared size");
      }
      return data.slice(headerLen, headerLen + decompressedSize);
    }

    if (decompressedSize > (data.length - headerLen) * MAX_EXPANSION) {
      throw new QuickLZError(
        `declared size ${decompressedSize} is more than ${data.length - headerLen} bytes can expand to`,
      );
    }
    const dest = new Uint8Array(decompressedSize);
    this.#hashtable.fill(0);

    const state: QlzState = {
      control: 1,
      sourcePos: headerLen,
      destPos: 0,
      nextHashed: 0,
    };

    while (state.destPos < decompressedSize) {
      this.#ensureControl(data, state);
      if ((state.control & 1) !== 0) {
        this.#processReference(data, dest, state);
      } else if (this.#processLiteral(data, dest, decompressedSize, state)) {
        break;
      }
    }

    if (state.destPos !== decompressedSize) throw new QuickLZError("block ends early");
    return dest;
  }

  #ensureControl(data: Uint8Array, st: QlzState): void {
    if (st.control !== 1) return;
    if (st.sourcePos + 4 > data.length) throw new QuickLZError("block ends early");
    st.control =
      (data[st.sourcePos]! |
        (data[st.sourcePos + 1]! << 8) |
        (data[st.sourcePos + 2]! << 16) |
        (data[st.sourcePos + 3]! << 24)) >>>
      0;
    st.sourcePos += 4;
  }

  #processReference(data: Uint8Array, dest: Uint8Array, st: QlzState): void {
    st.control = (st.control >>> 1) >>> 0;
    if (st.sourcePos + 2 > data.length) throw new QuickLZError("block ends early");

    const b1 = data[st.sourcePos]!;
    const b2 = data[st.sourcePos + 1]!;
    st.sourcePos += 2;

    const hash = (b1 >> 4) | (b2 << 4);
    let matchlen = b1 & 0x0f;
    if (matchlen !== 0) {
      matchlen += 2;
    } else {
      if (st.sourcePos >= data.length) throw new QuickLZError("block ends early");
      matchlen = data[st.sourcePos]!;
      st.sourcePos++;
    }

    const offset = this.#hashtable[hash]!;
    // The copy may overlap its own output, so only its start must precede it.
    if (offset >= st.destPos) throw new QuickLZError("back-reference points ahead of the output");
    if (st.destPos + matchlen > dest.length) {
      throw new QuickLZError("back-reference overruns the declared size");
    }
    for (let i = 0; i < matchlen; i++) {
      dest[st.destPos] = dest[offset + i]!;
      st.destPos++;
    }

    const end = st.destPos + 1 - matchlen;
    this.#updateHashtable(dest, st, end);
    st.nextHashed = st.destPos;
  }

  /** Copy literals; returns true once the tail of the block has been copied. */
  #processLiteral(
    data: Uint8Array,
    dest: Uint8Array,
    decompressedSize: number,
    st: QlzState,
  ): boolean {
    const threshold = Math.max(decompressedSize, 10) - 10;
    if (st.destPos >= threshold) {
      while (st.destPos < decompressedSize) {
        if (st.control === 1) {
          if (st.sourcePos + 4 > data.length) throw new QuickLZError("block ends early");
          st.control =
            (data[st.sourcePos]! |
              (data[st.sourcePos + 1]! << 8) |
              (data[st.sourcePos + 2]! << 16) |
              (data[st.sourcePos + 3]! << 24)) >>>
            0;
          st.sourcePos += 4;
        }
        if (st.sourcePos >= data.length) throw new QuickLZError("block ends early");
        dest[st.destPos++] = data[st.sourcePos++]!;
        st.control = (st.control >>> 1) >>> 0;
      }
      return true;
    }

    if (st.sourcePos >= data.length) throw new QuickLZError("block ends early");

    dest[st.destPos++] = data[st.sourcePos++]!;
    st.control = (st.control >>> 1) >>> 0;

    const end = Math.max(st.destPos - 2, 0);
    this.#updateHashtable(dest, st, end);
    if (st.nextHashed < end) st.nextHashed = end;

    return false;
  }

  #updateHashtable(dest: Uint8Array, st: QlzState, end: number): void {
    while (st.nextHashed < end) {
      if (st.nextHashed + 3 > dest.length) break;
      const v =
        (dest[st.nextHashed]! |
          (dest[st.nextHashed + 1]! << 8) |
          (dest[st.nextHashed + 2]! << 16)) >>>
        0;
      const hash = ((v >> 12) ^ v) & 0xfff;
      this.#hashtable[hash] = st.nextHashed;
      st.nextHashed++;
    }
  }
}

function parseQlzHeader(data: Uint8Array): {
  headerLen: number;
  decompressedSize: number;
  flags: number;
} {
  if (data.length < 3) throw new QuickLZError("data too short");

  const flags = data[0]!;
  const level = (flags >> 2) & 0x03;
  if (level !== 1) throw new QuickLZError("only level 1 is supported");

  const long = (flags & 0x02) !== 0;
  const headerLen = long ? 9 : 3;
  if (data.length < headerLen) throw new QuickLZError("data too short for header");

  // The header also carries the compressed size, which the reads below make
  // redundant: each one is checked against the data itself.
  const decompressedSize = long
    ? (data[5]! | (data[6]! << 8) | (data[7]! << 16) | (data[8]! << 24)) >>> 0
    : data[2]!;

  if (decompressedSize > MAX_DECOMPRESSED_SIZE) {
    throw new QuickLZError(`declared size ${decompressedSize} exceeds ${MAX_DECOMPRESSED_SIZE}`);
  }

  return { headerLen, decompressedSize, flags };
}
