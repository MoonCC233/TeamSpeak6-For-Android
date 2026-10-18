import { describe, expect, it } from "vitest";
import { KnownHosts, fingerprintOf, keyTypeOf } from "./knownhosts.js";
import type { PresentedHostKey } from "./knownhosts.js";

// Three ed25519 host keys, and their fingerprints as `ssh-keygen -lf` prints them.
const KEY_A = "AAAAC3NzaC1lZDI1NTE5AAAAIG8wzdJYXD226kBpXj8Ay+FFg9a18j6WglEOCRGS/IUt";
const KEY_B = "AAAAC3NzaC1lZDI1NTE5AAAAIGklKXDhBbE2nuXoAI+ZsvE526I+VwGs/1gF6Pk7Vx4y";
const KEY_C = "AAAAC3NzaC1lZDI1NTE5AAAAIOBhu6pg1vcTNvahn74Sx0lscxHt2cqnywC1SHblcFGa";
const FINGERPRINT_A = "SHA256:6cFaaAKd236Vs7mhSLRU8vzg0ea64h7nxKmkZgBce9k";
const FINGERPRINT_B = "SHA256:BX0Aqc7+ojfem3zokmoILt5D4S+8K3z6qkbA6rjDG7M";

// Hashed host fields in the form `ssh-keygen -H` writes, for KEY_A: one for the
// bare name `127.0.0.1` (port 22) and one for `[127.0.0.1]:10022`.
const HASHED_BARE = "|1|0ahSl2g9uLSz3yH2HEbZyPvyWnw=|Kj/nNYKQjXykSxsWJUmzqym36Uo=";
const HASHED_PORT = "|1|KO+AXGN/AG/93Q2w8eBEUn9Mm94=|s85aiM+MNOluqPpww4glLZ3h2YY=";

/** The key a server presents: its encoding, and what is read from it. */
function presented(host: string, port: number, encoded: string): PresentedHostKey {
  const key = new Uint8Array(Buffer.from(encoded, "base64"));
  return { host, port, key, keyType: keyTypeOf(key), fingerprint: fingerprintOf(key) };
}

function parse(...lines: string[]): KnownHosts {
  return KnownHosts.parse(lines.join("\n"), "known_hosts");
}

describe("the key's own encoding", () => {
  it("fingerprints a key as ssh-keygen does, and names its type", () => {
    const { key, keyType, fingerprint } = presented("h", 22, KEY_A);
    expect(fingerprint).toBe(FINGERPRINT_A);
    expect(keyType).toBe("ssh-ed25519");
    expect(fingerprintOf(key)).toBe(FINGERPRINT_A);
  });

  it("refuses an encoding that names no type", () => {
    expect(() => keyTypeOf(new Uint8Array([0, 0]))).toThrow("not a valid SSH public key");
    expect(() => keyTypeOf(new Uint8Array([0, 0, 0, 9, 0x61]))).toThrow(
      "not a valid SSH public key",
    );
    expect(() => keyTypeOf(new Uint8Array([0, 0, 0, 0, 0x61]))).toThrow(
      "not a valid SSH public key",
    );
  });
});

describe("KnownHosts", () => {
  describe("a plain entry", () => {
    const hosts = parse(`host.example.com ssh-ed25519 ${KEY_A}`);

    it("accepts the key it records", () => {
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
    });

    it("refuses another key for a host it records, naming both", () => {
      const attempt = () => hosts.verify(presented("host.example.com", 22, KEY_B));
      expect(attempt).toThrow(FINGERPRINT_B);
      expect(attempt).toThrow(FINGERPRINT_A);
      expect(attempt).toThrow("known_hosts records");
      expect(attempt).toThrow("something else is answering");
    });

    it("refuses a host it does not mention, and says to add it", () => {
      const attempt = () => hosts.verify(presented("other.example.com", 22, KEY_A));
      expect(attempt).toThrow("other.example.com is not in known_hosts");
      expect(attempt).toThrow(FINGERPRINT_A);
    });

    it("matches a host name in any case", () => {
      expect(() => hosts.verify(presented("HOST.Example.COM", 22, KEY_A))).not.toThrow();
    });

    it("keeps a comment at the end of the line out of the key", () => {
      const commented = parse(`host.example.com ssh-ed25519 ${KEY_A} ops@laptop and more`);
      expect(() => commented.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
    });

    it("reads tabs between the fields", () => {
      const tabbed = parse(`host.example.com\tssh-ed25519\t${KEY_A}`);
      expect(() => tabbed.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
    });
  });

  describe("the [host]:port form", () => {
    const hosts = parse(`[127.0.0.1]:10022 ssh-ed25519 ${KEY_A}`);

    it("matches that port and no other", () => {
      expect(() => hosts.verify(presented("127.0.0.1", 10022, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("127.0.0.1", 10023, KEY_A))).toThrow(
        "[127.0.0.1]:10023 is not in known_hosts",
      );
    });

    it("is not the entry for the bare name, which means port 22", () => {
      expect(() => hosts.verify(presented("127.0.0.1", 22, KEY_A))).toThrow(
        "127.0.0.1 is not in known_hosts",
      );
    });

    it("leaves a bare name to mean port 22 only", () => {
      const bare = parse(`127.0.0.1 ssh-ed25519 ${KEY_A}`);
      expect(() => bare.verify(presented("127.0.0.1", 22, KEY_A))).not.toThrow();
      expect(() => bare.verify(presented("127.0.0.1", 10022, KEY_A))).toThrow("is not in");
    });

    it("reads an IPv6 address in brackets, with or without the brackets given", () => {
      const v6 = parse(`[::1]:10022 ssh-ed25519 ${KEY_A}`);
      expect(() => v6.verify(presented("::1", 10022, KEY_A))).not.toThrow();
      expect(() => v6.verify(presented("[::1]", 10022, KEY_A))).not.toThrow();
    });
  });

  describe("a hashed entry", () => {
    it("matches the name it was hashed from, with the key it records", () => {
      const hosts = parse(`${HASHED_BARE} ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("127.0.0.1", 22, KEY_A))).not.toThrow();
    });

    it("matches the [host]:port form it was hashed from", () => {
      const hosts = parse(`${HASHED_PORT} ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("127.0.0.1", 10022, KEY_A))).not.toThrow();
    });

    it("refuses a different key for a hashed host", () => {
      const hosts = parse(`${HASHED_PORT} ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("127.0.0.1", 10022, KEY_B))).toThrow(
        "something else is answering",
      );
    });

    it("does not match a host it was not hashed from", () => {
      const hosts = parse(`${HASHED_PORT} ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("127.0.0.2", 10022, KEY_A))).toThrow("is not in");
      expect(() => hosts.verify(presented("127.0.0.1", 10023, KEY_A))).toThrow("is not in");
    });

    it("hashes the name in lower case, as a lookup does", () => {
      const hosts = parse(`${HASHED_BARE} ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("127.0.0.1", 22, KEY_A))).not.toThrow();
      // A name with letters: the entry above is for digits, so lower-casing must
      // not change what it hashes.
      expect(() => hosts.verify(presented("127.0.0.1", 22, KEY_B))).toThrow();
    });
  });

  describe("@revoked", () => {
    it("refuses a revoked key even for a host that records it", () => {
      const hosts = parse(
        `host.example.com ssh-ed25519 ${KEY_A}`,
        `@revoked * ssh-ed25519 ${KEY_A}`,
      );
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_A))).toThrow(
        "revoked in known_hosts:2",
      );
    });

    it("refuses a revoked key from any host, whatever the line names", () => {
      const hosts = parse(
        `@revoked old.example.com ssh-ed25519 ${KEY_A}`,
        `new.example.com ssh-ed25519 ${KEY_A}`,
      );
      expect(() => hosts.verify(presented("new.example.com", 22, KEY_A))).toThrow("revoked");
    });

    it("leaves a different key for the same host to its own entry", () => {
      const hosts = parse(
        `@revoked host.example.com ssh-ed25519 ${KEY_A}`,
        `host.example.com ssh-ed25519 ${KEY_B}`,
      );
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_B))).not.toThrow();
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_A))).toThrow("revoked");
    });

    it("is not an entry that accepts the key it revokes", () => {
      const hosts = parse(`@revoked host.example.com ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_B))).toThrow("is not in");
    });
  });

  describe("comments and blank lines", () => {
    const hosts = parse(
      "# a comment",
      "",
      "   ",
      `   # an indented comment`,
      `host.example.com ssh-ed25519 ${KEY_A}`,
      "",
      `# host.example.com ssh-ed25519 ${KEY_B}`,
    );

    it("skips them, and does not read a commented-out entry", () => {
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_B))).toThrow(
        "something else is answering",
      );
    });

    it("reads Windows line endings", () => {
      const crlf = KnownHosts.parse(
        `# c\r\nhost.example.com ssh-ed25519 ${KEY_A}\r\n`,
        "known_hosts",
      );
      expect(() => crlf.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
    });

    it("is an empty file that knows no host", () => {
      expect(() => parse("").verify(presented("host.example.com", 22, KEY_A))).toThrow("is not in");
    });
  });

  describe("host patterns", () => {
    it("accepts any of several keys recorded for one host", () => {
      const hosts = parse(
        `host.example.com ssh-ed25519 ${KEY_A}`,
        `host.example.com ssh-ed25519 ${KEY_B}`,
      );
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_B))).not.toThrow();
      expect(() => hosts.verify(presented("host.example.com", 22, KEY_C))).toThrow(
        `${FINGERPRINT_A} (line 1), ssh-ed25519 ${FINGERPRINT_B} (line 2)`,
      );
    });

    it("reads a comma-separated list", () => {
      const hosts = parse(`a.example.com,b.example.com,[c.example.com]:10022 ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("b.example.com", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("c.example.com", 10022, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("d.example.com", 22, KEY_A))).toThrow("is not in");
    });

    it("reads * and ? wildcards", () => {
      const hosts = parse(`*.example.com,node-?.lan ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("ts.example.com", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("node-1.lan", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("node-12.lan", 22, KEY_A))).toThrow("is not in");
      expect(() => hosts.verify(presented("example.org", 22, KEY_A))).toThrow("is not in");
    });

    it("lets a ! pattern exclude a host the others match", () => {
      const hosts = parse(`*.example.com,!secret.example.com ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("ts.example.com", 22, KEY_A))).not.toThrow();
      expect(() => hosts.verify(presented("secret.example.com", 22, KEY_A))).toThrow("is not in");
    });

    it("does not match on exclusions alone", () => {
      const hosts = parse(`!secret.example.com ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("ts.example.com", 22, KEY_A))).toThrow("is not in");
    });

    it("answers quickly for a pattern with many stars that cannot match", () => {
      const hosts = parse(`*a*a*a*a*a*a*a*a*a*a*b ssh-ed25519 ${KEY_A}`);
      const started = performance.now();
      expect(() => hosts.verify(presented("a".repeat(5000), 22, KEY_A))).toThrow("is not in");
      expect(performance.now() - started).toBeLessThan(1000);
    });
  });

  describe("@cert-authority", () => {
    it("is read and then ignored: a CA key does not accept a plain host key", () => {
      const hosts = parse(`@cert-authority *.example.com ssh-ed25519 ${KEY_A}`);
      expect(() => hosts.verify(presented("ts.example.com", 22, KEY_A))).toThrow("is not in");
    });
  });

  describe("a line it cannot read", () => {
    it.each([
      ["a missing key", "host.example.com ssh-ed25519", "expected a host, a key type and a key"],
      ["a missing type", "host.example.com", "expected a host, a key type and a key"],
      ["a key that is not base64", "host.example.com ssh-ed25519 !!!!", "not valid base64"],
      ["a key with trailing junk", `host.example.com ssh-ed25519 ${KEY_A}x`, "not valid base64"],
      [
        "a key that is not an encoding",
        "host.example.com ssh-ed25519 AAAA",
        "not a valid SSH public",
      ],
      [
        "a type the key contradicts",
        `host.example.com ssh-rsa ${KEY_A}`,
        "the key is ssh-ed25519, but the line says ssh-rsa",
      ],
      ["an unknown marker", `@trusted host.example.com ssh-ed25519 ${KEY_A}`, "unknown marker"],
      ["a marker with nothing after", "@revoked", "expected a host"],
      ["a hashed host with the wrong shape", `|1|abc ssh-ed25519 ${KEY_A}`, "cannot read the host"],
      [
        "a hashed host of another kind",
        `|2|AAAA|AAAA ssh-ed25519 ${KEY_A}`,
        "cannot read the host",
      ],
      ["a negation of nothing", `! ssh-ed25519 ${KEY_A}`, "cannot read the host"],
    ])("fails on %s, naming the line", (_name, line, message) => {
      expect(() => KnownHosts.parse(`# first\n${line}`, "known_hosts")).toThrow(message);
      expect(() => KnownHosts.parse(`# first\n${line}`, "known_hosts")).toThrow("known_hosts:2");
    });

    it("fails on the whole file rather than skipping a line, so a revocation is never lost", () => {
      expect(() =>
        parse(`@revoked old.example.com ssh-ed25519 !!!!`, `new.example.com ssh-ed25519 ${KEY_A}`),
      ).toThrow("known_hosts:1");
    });
  });
});
