import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { escape, unescape, buildCommandOrdered } from "./command.js";
import { parseCommand } from "./parser.js";
import { splitCommandRows } from "../helpers.js";

// Property tests over the command language. Its input comes from the network,
// so the parser and the row splitter must take anything without throwing, and
// what the encoder writes the parser must read back unchanged.
describe("the command language", () => {
  it("unescapes whatever it escaped", () => {
    fc.assert(fc.property(fc.string({ unit: "binary" }), (s) => unescape(escape(s)) === s));
  });

  it("reads back the parameters it encoded", () => {
    const key = fc.stringMatching(/^[a-z_]{1,12}$/);
    fc.assert(
      fc.property(
        fc.uniqueArray(fc.tuple(key, fc.string({ unit: "binary" })), {
          selector: ([k]) => k,
          maxLength: 8,
        }),
        (params) => {
          const parsed = parseCommand(buildCommandOrdered("cmd", params));
          expect(parsed?.name).toBe("cmd");
          expect(parsed?.params).toEqual(Object.fromEntries(params));
        },
      ),
    );
  });

  it("keeps a parameter named after an Object.prototype member", () => {
    // fast-check found this: a plain object swallowed __proto__=.
    const params = parseCommand("cmd __proto__= constructor=x")?.params ?? {};
    expect(Object.entries(params)).toEqual([
      ["__proto__", ""],
      ["constructor", "x"],
    ]);
    expect(parseCommand("cmd a=1")?.params["toString"]).toBeUndefined();
  });

  it("parses and splits any line without throwing", () => {
    fc.assert(
      fc.property(fc.string({ unit: "binary", maxLength: 400 }), (line) => {
        parseCommand(line);
        for (const row of splitCommandRows(line)) parseCommand(row);
      }),
      { numRuns: 2000 },
    );
  });
});
