import { afterEach, describe, expect, it } from "vitest";
import fc from "fast-check";
import { createServer } from "node:net";
import type { AddressInfo, Server } from "node:net";
import { MAX_DISCOVERY_RESPONSE, queryTSDNS } from "./resolver.js";

// A TSDNS server answers over plain TCP with one line: the address, or 404.
// Whoever is on the path can send anything instead, in any chunking. Reading it
// must give a line or nothing, never throw, never hold more than the bound, and
// never wait for the end of an answer that is already over the bound.

let server: Server | undefined;
afterEach(() => {
  server?.close();
  server = undefined;
});

/** A loopback server that answers the first query with `chunks`, then closes. */
async function serving(chunks: readonly Uint8Array[]): Promise<string> {
  const listening = createServer((socket) => {
    socket.once("data", () => {
      for (const chunk of chunks) socket.write(chunk);
      socket.end();
    });
    socket.on("error", () => {});
  });
  server = listening;
  await new Promise<void>((resolve) => listening.listen(0, "127.0.0.1", resolve));
  return `127.0.0.1:${(listening.address() as AddressInfo).port}`;
}

/** What the answer should read as: its first line, unless that is empty or an error. */
function expected(answer: Uint8Array): string | null {
  const text = Buffer.from(answer).toString("latin1");
  const end = text.indexOf("\n");
  if (end < 0 || end >= MAX_DISCOVERY_RESPONSE) return null;
  const line = Buffer.from(text.slice(0, end), "latin1").toString("utf8").trim();
  return line === "" || line === "404" || line === "errors" ? null : line;
}

describe("a TSDNS answer", () => {
  it("reads as its first line, or as nothing, in whatever chunks it arrives", async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.oneof(
          fc.stringMatching(/^[a-z0-9.:-]{0,40}$/),
          fc.constantFrom("404", "errors", "  ", "a b", "\r"),
          fc.string({ unit: "binary", maxLength: 40 }).map((s) => s.replaceAll("\n", "")),
        ),
        fc.string({ unit: "binary", maxLength: 40 }),
        fc.integer({ min: 1, max: 7 }),
        async (line, tail, size) => {
          const answer = Buffer.from(`${line}\n${tail}`, "latin1");
          const chunks: Uint8Array[] = [];
          for (let i = 0; i < answer.length; i += size) chunks.push(answer.subarray(i, i + size));
          const address = await serving(chunks);
          const got = await queryTSDNS(address, "example.com");
          expect(got).toBe(expected(answer));
          server?.close();
        },
      ),
      { numRuns: 60 },
    );
  }, 60_000);

  it("is refused once it is longer than the bound without a line", async () => {
    const address = await serving([new Uint8Array(MAX_DISCOVERY_RESPONSE + 100).fill(0x61)]);
    const started = Date.now();
    await expect(queryTSDNS(address, "example.com")).resolves.toBeNull();
    expect(Date.now() - started).toBeLessThan(2_000);
  });
});
