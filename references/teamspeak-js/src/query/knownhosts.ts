import { createHash, createHmac, timingSafeEqual } from "node:crypto";

/**
 * What a host key check needs to know about the key a server presented. A
 * structural type, so this module reads no further than it uses.
 */
export interface PresentedHostKey {
  /** The host that was dialled, as the caller wrote it. */
  readonly host: string;
  /** The port that was dialled. */
  readonly port: number;
  /** The key's wire encoding: the blob a `known_hosts` line carries in base64. */
  readonly key: Uint8Array;
  /** The key's algorithm, as its own encoding names it. */
  readonly keyType: string;
  /** The key's `SHA256:` fingerprint, as `ssh-keygen -lf` prints it. */
  readonly fingerprint: string;
}

/**
 * The OpenSSH fingerprint of a key: `SHA256:` and the unpadded base64 of the
 * SHA-256 of its wire encoding, which is what `ssh-keygen -lf` prints.
 */
export function fingerprintOf(key: Uint8Array): string {
  return `SHA256:${createHash("sha256").update(key).digest("base64").replace(/=+$/, "")}`;
}

/**
 * The algorithm a key's wire encoding names, such as `ssh-ed25519`: the first
 * field of the encoding, a length-prefixed string.
 *
 * Throws on an encoding that is too short to hold one, so that a key which
 * cannot be read is refused rather than described as nothing.
 */
export function keyTypeOf(key: Uint8Array): string {
  if (key.length >= 4) {
    const length = new DataView(key.buffer, key.byteOffset, key.byteLength).getUint32(0);
    if (length > 0 && length <= key.length - 4) {
      return Buffer.from(key.subarray(4, 4 + length)).toString("latin1");
    }
  }
  throw new Error("the host key is not a valid SSH public key encoding");
}

/** The port OpenSSH leaves out of a `known_hosts` host: SSH's own. */
const DEFAULT_PORT = 22;

/**
 * A host as `known_hosts` writes it, and as hashed entries hash it: the bare
 * name for port 22, `[name]:port` for any other. Lower-cased, since host names
 * are not case sensitive and OpenSSH looks them up that way.
 */
function addressOf(host: string, port: number): string {
  const name = (
    host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host
  ).toLowerCase();
  return port === DEFAULT_PORT ? name : `[${name}]:${port}`;
}

/**
 * Whether `text` matches `pattern`, where `*` stands for any run of characters
 * and `?` for exactly one.
 *
 * Written as a loop that backs up to the last `*` rather than as a regular
 * expression: a pattern with several stars would make a regular expression
 * backtrack exponentially on a name that does not match.
 */
function wildcardMatch(pattern: string, text: string): boolean {
  let p = 0;
  let t = 0;
  let star = -1;
  let resume = 0;
  while (t < text.length) {
    const wanted = pattern[p];
    if (wanted === "*") {
      star = p++;
      resume = t;
    } else if (wanted === "?" || wanted === text[t]) {
      p++;
      t++;
    } else if (star >= 0) {
      p = star + 1;
      t = ++resume;
    } else {
      return false;
    }
  }
  while (pattern[p] === "*") p++;
  return p === pattern.length;
}

/** Whether a line's host field names this address. */
type HostMatcher = (address: string) => boolean;

/** One key a `known_hosts` file records, and where. */
interface Entry {
  readonly line: number;
  readonly matches: HostMatcher;
  readonly keyType: string;
  readonly key: Uint8Array;
}

/** The base64 in a key field, strictly: `Buffer.from` would skip what it cannot read. */
function decodeBase64(text: string): Uint8Array | undefined {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(text)) return undefined;
  const bytes = Buffer.from(text, "base64");
  return bytes.toString("base64") === text ? new Uint8Array(bytes) : undefined;
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}

/** `|1|salt|hash`: the host field `ssh-keygen -H` writes, an HMAC-SHA1 of the address. */
function hashedMatcher(field: string): HostMatcher | undefined {
  const parts = field.split("|");
  if (parts.length !== 4 || parts[1] !== "1") return undefined;
  const salt = decodeBase64(parts[2] ?? "");
  const hash = decodeBase64(parts[3] ?? "");
  if (salt === undefined || hash === undefined) return undefined;
  return (address) => sameBytes(createHmac("sha1", salt).update(address).digest(), hash);
}

/**
 * A comma-separated host field: patterns with `*` and `?`, `[host]:port` for a
 * port other than 22, and `!` to exclude. An address matches when some pattern
 * does and no excluded one does.
 */
function patternMatcher(field: string): HostMatcher | undefined {
  const patterns: Array<{ readonly negated: boolean; readonly pattern: string }> = [];
  for (const item of field.split(",")) {
    if (item === "") continue;
    const negated = item.startsWith("!");
    const pattern = (negated ? item.slice(1) : item).toLowerCase();
    if (pattern === "") return undefined;
    patterns.push({ negated, pattern });
  }
  if (patterns.length === 0) return undefined;
  return (address) => {
    let matched = false;
    for (const { negated, pattern } of patterns) {
      if (!wildcardMatch(pattern, address)) continue;
      if (negated) return false;
      matched = true;
    }
    return matched;
  };
}

/**
 * An OpenSSH `known_hosts` file, read for the one question a client asks of it:
 * is this the key the host is recorded with.
 *
 * Follows what `ssh` does with `StrictHostKeyChecking=yes`: a host the file
 * does not mention is refused, so is one whose recorded keys do not include the
 * key it presented, and so is any key a `@revoked` line names, whatever host
 * presents it. Nothing is ever added to the file.
 *
 * `@cert-authority` lines are read and then ignored. They name a key that signs
 * host certificates, and a presented plain key is not one, so they can neither
 * accept nor refuse it.
 */
export class KnownHosts {
  readonly #source: string;
  readonly #entries: readonly Entry[];
  readonly #revoked: readonly Entry[];

  private constructor(source: string, entries: readonly Entry[], revoked: readonly Entry[]) {
    this.#source = source;
    this.#entries = entries;
    this.#revoked = revoked;
  }

  /**
   * Read the text of a `known_hosts` file. `source` names it in messages.
   *
   * Throws on a line it cannot read, naming the file and line, rather than
   * skipping it: a skipped `@revoked` line would let the key it revokes
   * through.
   */
  static parse(text: string, source: string): KnownHosts {
    const entries: Entry[] = [];
    const revoked: Entry[] = [];
    const lines = text.split(/\r?\n/);
    for (const [index, raw] of lines.entries()) {
      const line = raw.trim();
      if (line === "" || line.startsWith("#")) continue;
      const where = `${source}:${index + 1}`;
      const fields = line.split(/[ \t]+/);

      let marker: string | undefined;
      if (fields[0]?.startsWith("@")) {
        marker = fields.shift();
        if (marker !== "@revoked" && marker !== "@cert-authority") {
          throw new Error(`${where}: unknown marker ${JSON.stringify(marker)}`);
        }
      }
      const [hostField, keyType, encoded] = fields;
      if (hostField === undefined || keyType === undefined || encoded === undefined) {
        throw new Error(`${where}: expected a host, a key type and a key`);
      }
      const key = decodeBase64(encoded);
      if (key === undefined) throw new Error(`${where}: the key is not valid base64`);
      let named: string;
      try {
        named = keyTypeOf(key);
      } catch {
        throw new Error(`${where}: the key is not a valid SSH public key encoding`);
      }
      if (named !== keyType) {
        throw new Error(`${where}: the key is ${named}, but the line says ${keyType}`);
      }
      const matches = hostField.startsWith("|")
        ? hashedMatcher(hostField)
        : patternMatcher(hostField);
      if (matches === undefined) {
        throw new Error(`${where}: cannot read the host ${JSON.stringify(hostField)}`);
      }

      const entry: Entry = { line: index + 1, matches, keyType, key };
      if (marker === "@revoked") revoked.push(entry);
      else if (marker === undefined) entries.push(entry);
    }
    return new KnownHosts(source, entries, revoked);
  }

  /**
   * Accept the key by returning, or refuse it by throwing an `Error` that says
   * why: revoked, a host the file does not know, or a key that differs from the
   * recorded one.
   */
  verify(presented: PresentedHostKey): void {
    const label = `${presented.keyType} ${presented.fingerprint}`;
    const revoked = this.#revoked.find((entry) => sameBytes(entry.key, presented.key));
    if (revoked !== undefined) {
      throw new Error(
        `the host key ${label} is revoked in ${this.#source}:${revoked.line}, so it is not accepted from any host`,
      );
    }

    const address = addressOf(presented.host, presented.port);
    const recorded = this.#entries.filter((entry) => entry.matches(address));
    if (recorded.length === 0) {
      throw new Error(
        `${address} is not in ${this.#source}; its key ${label} has to be added before connecting`,
      );
    }
    if (recorded.some((entry) => sameBytes(entry.key, presented.key))) return;

    const known = recorded
      .map((entry) => `${entry.keyType} ${fingerprintOf(entry.key)} (line ${entry.line})`)
      .join(", ");
    throw new Error(
      `${address} presented ${label}, but ${this.#source} records ${known}: the server's key changed, or something else is answering this port`,
    );
  }
}
