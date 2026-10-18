import { InvalidIdentityError } from "../errors.js";
import { describe, it, expect } from "vitest";
import { inspect } from "node:util";
import {
  generateIdentity,
  generateIdentitySync,
  parseIdentity,
  uidFromPublicKey,
  uidAlgorithmForVersion,
} from "./identity.js";

describe("Identity", () => {
  it("generateIdentitySync produces a valid identity at level 8", () => {
    const id = generateIdentitySync(8);
    expect(id.securityLevel()).toBeGreaterThanOrEqual(8);
  });

  it("publicKey returns a non-empty string", () => {
    const id = generateIdentitySync(8);
    const pub = id.publicKey();
    expect(pub.length).toBeGreaterThan(0);
    // Should be valid base64
    expect(() => Buffer.from(pub, "base64")).not.toThrow();
  });

  it("export / parseIdentity round-trips", () => {
    const id = generateIdentitySync(8);
    const str = id.export();
    const id2 = parseIdentity(str);
    // Same offset
    expect(id2.offset).toBe(id.offset);
    // Same public key
    expect(id2.publicKey()).toBe(id.publicKey());
  });

  // toString() used to return the private key, so an identity interpolated
  // into a log line or an error message leaked it.
  it("keeps the private key out of every way it can be printed", () => {
    const id = generateIdentitySync(0);
    const secret = id.export().split(":")[0]!;

    for (const printed of [
      String(id),
      // oxlint-disable-next-line typescript/restrict-template-expressions -- the template form is one of the leaks under test
      `${id}`,
      JSON.stringify({ id }),
      inspect(id),
      inspect({ nested: id }, { depth: 5 }),
    ]) {
      expect(printed).not.toContain(secret);
      expect(printed).toContain("redacted");
    }
  });

  it("generateIdentity is async and honours its signal", async () => {
    const id = await generateIdentity(4);
    expect(id.securityLevel()).toBeGreaterThanOrEqual(4);

    const reason = new Error("stop");
    await expect(generateIdentity(60, { signal: AbortSignal.abort(reason) })).rejects.toBe(reason);
  });

  it("parseIdentity rejects invalid strings", () => {
    expect(() => parseIdentity("notvalid")).toThrow();
  });

  it("uidFromPublicKey is base64 of the hash of the public key", () => {
    const id = generateIdentitySync(8);
    const pub = id.publicKey();
    const uid = uidFromPublicKey(pub, "sha1");
    expect(uid.length).toBeGreaterThan(0);
    // Base64 character set
    expect(uid).toMatch(/^[A-Za-z0-9+/]+=*$/);
  });

  // The UID hash is not negotiable on the wire: TS3 hashes with SHA-1 and TS6
  // with SHA-256, so a client that assumes one silently computes a UID the
  // other server never sends.
  it("uidAlgorithmForVersion picks SHA-1 for TS3 and SHA-256 for TS6", () => {
    expect(uidAlgorithmForVersion("3.13.8 [Build: 1779874471]")).toBe("sha1");
    expect(uidAlgorithmForVersion("3.0.11 [Build: 1374563791]")).toBe("sha1");
    expect(uidAlgorithmForVersion("6.0.0-beta13.1 [Build: 1790080330]")).toBe("sha256");
    expect(uidAlgorithmForVersion("6.1.0")).toBe("sha256");
    // An unparseable version stays with the TS3 default rather than throwing.
    expect(uidAlgorithmForVersion("")).toBe("sha1");
    expect(uidAlgorithmForVersion("unknown")).toBe("sha1");
  });

  it("uidFromPublicKey uses the requested algorithm", () => {
    const id = generateIdentitySync(8);
    const pub = id.publicKey();
    const sha1Uid = uidFromPublicKey(pub, "sha1");
    const sha256Uid = uidFromPublicKey(pub, "sha256");
    // 20 bytes and 32 bytes base64-encode to 28 and 44 characters, so the two
    // digests cannot be confused for one another.
    expect(sha1Uid).toHaveLength(28);
    expect(sha256Uid).toHaveLength(44);
    expect(sha1Uid).not.toBe(sha256Uid);
    expect(id.uid("sha256")).toBe(sha256Uid);
  });

  it("is immutable: upgrading returns a new identity and leaves this one alone", async () => {
    const id = generateIdentitySync(0);
    const upgraded = await id.upgradeToLevel(6);
    expect(upgraded.securityLevel()).toBeGreaterThanOrEqual(6);
    expect(upgraded.publicKey()).toBe(id.publicKey());
    expect(id.offset).toBe(0n);
    expect(await upgraded.upgradeToLevel(1)).toBe(upgraded);
    expect(() => {
      (id as { offset: bigint }).offset = 5n;
    }).toThrow(TypeError);
    expect(Object.keys(id)).toEqual([]);
  });

  it("refuses a level SHA-1 cannot reach", async () => {
    await expect(generateIdentitySync(0).upgradeToLevel(161)).rejects.toBeInstanceOf(RangeError);
    expect(() => generateIdentitySync(161)).toThrow(RangeError);
  });

  // Node's base64 decoder skips what it cannot read, so these used to load as
  // some other key, and an offset past 64 bits was taken as given.
  it("parseIdentity takes only canonical base64 and a uint64 offset", () => {
    const [key, offset] = generateIdentitySync(0).export().split(":") as [string, string];
    expect(parseIdentity(`${key}:${offset}`).export()).toBe(`${key}:${offset}`);
    expect(parseIdentity(`${key}:${(1n << 64n) - 1n}`).offset).toBe((1n << 64n) - 1n);

    const bad = [
      `${key}:${1n << 64n}`,
      `${key}:+1`,
      `${key}:`,
      `:${offset}`,
      `!!!!${key}:${offset}`,
      `${key.slice(0, -1)}:${offset}`, // without its padding
      `${key} :${offset}`,
      `${"x".repeat(10)}:0`,
      `${Buffer.alloc(33, 1).toString("base64")}:0`,
    ];
    for (const value of bad) {
      expect(() => parseIdentity(value), value).toThrow(InvalidIdentityError);
    }
  });

  it("parseIdentity throws only InvalidIdentityError", () => {
    for (const bad of ["notvalid", "abc:1x", ":-1", "AAAA:99999999999999999999999999x", "AAAA:1"]) {
      expect(() => parseIdentity(bad), bad).toThrow(InvalidIdentityError);
    }
  });
});
