import { isCommandName } from "./command/parser.js";

const MAX_UINT64 = (1n << 64n) - 1n;
const MAX_UINT64_DIGITS = String(MAX_UINT64).length;

/**
 * Read an unsigned 64-bit decimal: digits only, 0 to 2^64-1. Anything else — a
 * sign, a hex or binary prefix, a fraction, a value that does not fit — reads as
 * 0, which names nothing, instead of throwing: the text comes off the network.
 * `BigInt()` alone takes all of those, in any width.
 */
export function parseUint64(s: string): bigint {
  if (!/^[0-9]+$/.test(s)) return 0n;
  // Leading zeros are dropped first so that no long run of digits reaches BigInt.
  const digits = s.replace(/^0+(?=[0-9])/, "");
  if (digits.length > MAX_UINT64_DIGITS) return 0n;
  const value = BigInt(digits);
  return value > MAX_UINT64 ? 0n : value;
}

export function parseUint16(s: string): number {
  const v = parseInt(s, 10);
  if (isNaN(v) || v < 0 || v > 65535) return 0;
  return v;
}

export function parseInt10(s: string): number {
  const v = parseInt(s, 10);
  return isNaN(v) ? 0 : v;
}

/** The bytes the rows of a line may hold together, see {@link rowLimits}. */
const MIN_ROW_BUDGET = 64 * 1024;
const ROW_GROWTH = 4;
const MAX_ROW_AMPLIFICATION = 64;

/**
 * Expand a pipe-separated multi-row TS3 command line into individual rows.
 *
 * A named line (`notifyclientleftview a=1|a=2`) keeps its name on every row; a
 * nameless response (`cldbid=1|cldbid=2`) stays nameless. Whether the first
 * token is a name is decided by the parser's own rule — deciding it here by
 * position is how a nameless batch with no space in it went through unsplit and
 * a one-field-per-row response came back as a single row. Tokens are split on
 * the space and on nothing else, as the parser splits them.
 *
 * TeamSpeak compresses a batch by leaving out of a row the fields whose value
 * is unchanged from the previous row: in a notifycliententerview batch, ctid is
 * often present only on the first row. A row is therefore the fields of the
 * previous row, as that row came out, that it does not name itself, followed by
 * its own. Tokens stay escaped so the command parser can still perform the
 * single unescape pass later.
 *
 * A row that names a field of its own each time makes every row carry all the
 * fields before it, so the rows would grow with the square of the line. The
 * rows of a real server's line are a little larger than their own parts and
 * never come near the limits that stop this: once the rows together would hold
 * more than the larger of four times the line and 64 KiB, the rows after that
 * carry only their own tokens. A name repeated on every row can grow them on its
 * own, so at twice that the rows stop, and the rest of the line is dropped.
 * Neither limit passes 64 times the line. Lengths are counted in characters.
 */
export function splitCommandRows(line: string): string[] {
  const spaceIdx = line.indexOf(" ");
  const head = spaceIdx < 0 ? line : line.slice(0, spaceIdx);
  const name = isCommandName(head) ? head : "";
  const rest = name === "" ? line : spaceIdx < 0 ? "" : line.slice(spaceIdx + 1);

  if (!rest.includes("|")) return [line];

  const prefix = name === "" ? "" : `${name} `;
  const { inherit: inheritLimit, drop: dropLimit } = rowLimits(line.length);
  const rows: string[] = [];
  let used = 0;
  // The fields of the row before, each once, with the token that row gave it;
  // empty once rows stop inheriting.
  let previous: string[] = [];
  let inheriting = true;

  for (const part of rest.split("|")) {
    if (part === "") continue;
    const tokens = part.split(" ").filter(Boolean);

    let row = prefix + tokens.join(" ");
    if (inheriting) {
      const resolved = resolveRow(previous, tokens);
      const inherited = prefix + resolved.join(" ");
      if (used + inherited.length <= inheritLimit) {
        row = inherited;
        previous = lastPerField(resolved);
      } else {
        inheriting = false;
        previous = [];
      }
    }
    if (used + row.length > dropLimit) break;
    used += row.length;
    rows.push(row);
  }
  return rows.length === 0 ? [line] : rows;
}

/**
 * The tokens of a row: the fields of the previous row that the row does not
 * name, then the row's own tokens.
 */
function resolveRow(previous: string[], tokens: string[]): string[] {
  const named = new Set<string>();
  for (const token of tokens) named.add(fieldName(token));
  return [...previous.filter((token) => !named.has(fieldName(token))), ...tokens];
}

/** The last token of each field, in the order the fields first appear. */
function lastPerField(tokens: string[]): string[] {
  const at = new Map<string, number>();
  const kept: string[] = [];
  for (const token of tokens) {
    const key = fieldName(token);
    const i = at.get(key);
    if (i === undefined) {
      at.set(key, kept.length);
      kept.push(token);
    } else {
      kept[i] = token;
    }
  }
  return kept;
}

/**
 * How many characters the rows of a line of `n` may hold together before they
 * stop inheriting fields, and before they stop.
 */
function rowLimits(n: number): { inherit: number; drop: number } {
  const inherit = Math.max(ROW_GROWTH * n, MIN_ROW_BUDGET);
  const drop = Math.min(2 * inherit, MAX_ROW_AMPLIFICATION * n);
  return { inherit: Math.min(inherit, drop), drop };
}

function fieldName(token: string): string {
  const equalIndex = token.indexOf("=");
  return equalIndex > 0 ? token.slice(0, equalIndex) : token;
}

/**
 * Sleep, or wake early once `signal` aborts. Resolves either way, never
 * rejects. The timer keeps the process alive while it runs.
 */
export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  if (ms <= 0 || signal?.aborted) return Promise.resolve();

  return new Promise((resolve) => {
    const done = (): void => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    signal?.addEventListener("abort", done, { once: true });
  });
}
