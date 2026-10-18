import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { isIP } from "node:net";
import { joinHostPort, parseConnectAddress, splitHostPort } from "./address.js";
import { InvalidAddressError } from "./errors.js";

// An address is typed by a user, or handed back by a TSDNS server or the
// nickname service: text that anyone on the path may choose. Splitting it must
// take any text, and what joinHostPort writes splitHostPort must read back.

const label = fc.stringMatching(/^[a-z0-9][a-z0-9-]{0,10}$/);
const hostName = fc.array(label, { minLength: 1, maxLength: 4 }).map((labels) => labels.join("."));
const ipv6 = fc
  .array(fc.integer({ min: 0, max: 0xffff }), { minLength: 8, maxLength: 8 })
  .map((groups) => groups.map((g) => g.toString(16)).join(":"));
const port = fc.integer({ min: 1, max: 65535 }).map(String);

describe("TeamSpeak addresses", () => {
  it("are split without throwing, into a host and a port of digits", () => {
    fc.assert(
      fc.property(fc.string({ unit: "binary", maxLength: 60 }), (address) => {
        const { host, port: p } = splitHostPort(address);
        expect(typeof host).toBe("string");
        expect(p === "9987" || /^\d+$/.test(p)).toBe(true);
      }),
      { numRuns: 2000 },
    );
  });

  it("read back a host and port that joinHostPort wrote", () => {
    fc.assert(
      fc.property(fc.oneof(hostName, ipv6), port, (host, p) => {
        expect(splitHostPort(joinHostPort(host, p))).toEqual({ host, port: p });
      }),
      { numRuns: 2000 },
    );
  });

  it("are accepted as an address to connect to, or refused with an InvalidAddressError", () => {
    fc.assert(
      fc.property(fc.string({ unit: "binary", maxLength: 60 }), (address) => {
        try {
          const parsed = parseConnectAddress(address);
          expect(parsed.portNumber).toBeGreaterThanOrEqual(1);
          expect(parsed.portNumber).toBeLessThanOrEqual(65535);
          expect(parsed.host).not.toMatch(/[\s\p{Cc}/?#@[\]\\]/u);
          expect(parsed.host.includes(":") ? isIP(parsed.host) === 6 : true).toBe(true);
        } catch (error) {
          expect(error).toBeInstanceOf(InvalidAddressError);
        }
      }),
      { numRuns: 3000 },
    );
  });

  it("connect to the host and port they name, when they name one", () => {
    fc.assert(
      fc.property(fc.oneof(hostName, ipv6), port, (host, p) => {
        const parsed = parseConnectAddress(joinHostPort(host, p));
        expect(parsed).toEqual({ host, port: p, portNumber: Number(p) });
      }),
      { numRuns: 1000 },
    );
  });
});

describe("a port in the address", () => {
  it("is the default one when there is none", () => {
    fc.assert(
      fc.property(hostName, (host) => {
        expect(splitHostPort(host).port).toBe("9987");
      }),
      { numRuns: 200 },
    );
  });
});
