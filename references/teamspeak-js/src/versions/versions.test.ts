import { createPublicKey, verify } from "node:crypto";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { ClientVersion } from "@honeybbq/teamspeak";
import * as table from "./index.js";

const PUBLIC_KEY = "UrN1jX0dBE1vulTNLCoYwrVpfITyo+NBuq/twbf9hLw=";
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
const key = createPublicKey({
  key: Buffer.concat([SPKI_PREFIX, Buffer.from(PUBLIC_KEY, "base64")]),
  format: "der",
  type: "spki",
});

const platforms = ["Windows", "Linux", "OS X", "macOS", "Android", "iOS"];
const entries = Object.entries(table);

/** The name an entry takes from its row: `<Platform>_<version>_<build>[_<SUFFIX>]`. */
function nameOf(row: ClientVersion): string {
  const m = /^(.+) \[Build: (\d+)\]( [A-Z]+)?$/.exec(row.version);
  if (!m) throw new Error(`bad version ${row.version}`);
  const platform = row.platform === "OS X" ? "OSX" : row.platform;
  const text = m[1]!.replace(/[.\-_]/g, "_").replace(/\?/g, "X");
  const suffix = m[3] === undefined ? "" : `_${m[3].trim()}`;
  return `${platform}_${text}_${m[2]}${suffix}`;
}

describe("the version table", () => {
  it("holds entries", () => {
    expect(entries.length).toBeGreaterThan(1000);
  });

  it("signs every entry: the signature verifies over platform + version", () => {
    for (const [name, row] of entries) {
      const signature = Buffer.from(row.sign, "base64");
      expect(signature.length, name).toBe(64);
      expect(signature.toString("base64"), name).toBe(row.sign);
      const ok = verify(null, Buffer.from(row.platform + row.version, "ascii"), key, signature);
      expect(ok, name).toBe(true);
    }
  });

  it("obeys the row rules", () => {
    for (const [name, row] of entries) {
      expect(Object.keys(row), name).toEqual(["platform", "version", "sign"]);
      expect(platforms, name).toContain(row.platform);
      expect(row.version, name).toMatch(/^(.+) \[Build: (\d+)\]( [A-Z]+)?$/);
    }
  });

  it("names every export after its row", () => {
    for (const [name, row] of entries) expect(name).toBe(nameOf(row));
  });

  it("has no platform and version twice", () => {
    const seen = new Set<string>();
    for (const [name, row] of entries) {
      const pair = `${row.platform}\0${row.version}`;
      expect(seen.has(pair), name).toBe(false);
      seen.add(pair);
    }
  });

  it("is sorted by build, then platform, then version, as the source reads", () => {
    const source = readFileSync(new URL("./index.ts", import.meta.url), "utf8");
    const declared = [...source.matchAll(/^export const (\w+): ClientVersion = \{$/gm)].map(
      (m) => m[1]!,
    );
    expect(declared.length).toBe(entries.length);
    expect(new Set(declared)).toEqual(new Set(entries.map(([name]) => name)));

    const rows = declared.map((name) => (table as Record<string, ClientVersion>)[name]!);
    const build = (r: ClientVersion) => Number(/\[Build: (\d+)\]/.exec(r.version)![1]);
    for (let i = 1; i < rows.length; i++) {
      const a = rows[i - 1]!;
      const b = rows[i]!;
      const order =
        build(a) - build(b) ||
        platforms.indexOf(a.platform) - platforms.indexOf(b.platform) ||
        (a.version < b.version ? -1 : a.version > b.version ? 1 : 0);
      expect(order, `${declared[i - 1]} before ${declared[i]}`).toBeLessThan(0);
    }
  });
});
