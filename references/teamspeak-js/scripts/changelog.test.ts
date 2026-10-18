import { beforeEach, describe, expect, it, vi } from "vitest";

const { original } = vi.hoisted(() => ({ original: vi.fn<() => Promise<string>>() }));
vi.mock("@changesets/changelog-github", () => ({
  default: { getReleaseLine: original, getDependencyReleaseLine: vi.fn<() => Promise<string>>() },
}));

// @ts-expect-error The formatter is plain JavaScript.
const { createChangelog } = await import("../.changeset/changelog.mjs");

const options = { repo: "HoneyBBQ/teamspeak-js", maintainers: ["HoneyBBQ"] };
const pull = "[#7](https://github.com/HoneyBBQ/teamspeak-js/pull/7)";
const commit = "[`abc1234`](https://github.com/HoneyBBQ/teamspeak-js/commit/abc1234)";
const maintainer = "[@HoneyBBQ](https://github.com/HoneyBBQ)";
const alice = "[@alice](https://github.com/alice)";
const bob = "[@bob](https://github.com/bob)";
const bot = "[@dependabot](https://github.com/apps/dependabot)";

/** What changelog-github writes for a change credited to `users`. */
const written = (users: string) => `\n\n- ${pull} ${commit} Thanks ${users}! - Fix it\n`;
const unthanked = `\n\n- ${pull} ${commit} - Fix it\n`;

type Lookup = (login: string, entry: { pull?: number; commit?: string }) => Promise<boolean>;

async function format(users: string, isFirst: Lookup): Promise<string> {
  original.mockResolvedValue(written(users));
  const changelog = createChangelog(isFirst);
  return changelog.getReleaseLine({ summary: "Fix it" }, "patch", options);
}

describe("changelog", () => {
  beforeEach(() => original.mockReset());

  it("thanks a first contribution", async () => {
    const isFirst = vi.fn<Lookup>(async () => true);
    expect(await format(alice, isFirst)).toBe(written(alice));
    expect(isFirst).toHaveBeenCalledWith("alice", expect.objectContaining({ pull: 7 }));
  });

  it("does not thank a returning contributor, and keeps the links", async () => {
    expect(await format(alice, async () => false)).toBe(unthanked);
  });

  it("does not thank a maintainer, without asking", async () => {
    const isFirst = vi.fn<Lookup>(async () => true);
    expect(await format(maintainer, isFirst)).toBe(unthanked);
    expect(isFirst).not.toHaveBeenCalled();
  });

  it("does not thank a bot, without asking", async () => {
    const isFirst = vi.fn<Lookup>(async () => true);
    expect(await format(bot, isFirst)).toBe(unthanked);
    expect(isFirst).not.toHaveBeenCalled();
  });

  it("does not thank anyone when the lookup fails, and does not throw", async () => {
    const failing = async () => {
      throw new Error("offline");
    };
    expect(await format(alice, failing)).toBe(unthanked);
  });

  it("thanks only the new contributor of a mixed credit", async () => {
    const isFirst = async (login: string) => login === "bob";
    expect(await format(`${maintainer}, ${alice}, ${bob}`, isFirst)).toBe(written(bob));
  });

  it("asks once per user and entry", async () => {
    original.mockResolvedValue(written(alice));
    const isFirst = vi.fn<Lookup>(async () => true);
    const changelog = createChangelog(isFirst);
    await changelog.getReleaseLine({ summary: "" }, "patch", options);
    await changelog.getReleaseLine({ summary: "" }, "patch", options);
    expect(isFirst).toHaveBeenCalledTimes(1);
  });

  it("leaves a line without a credit alone", async () => {
    original.mockResolvedValue(unthanked);
    const changelog = createChangelog(async () => true);
    expect(await changelog.getReleaseLine({ summary: "" }, "patch", options)).toBe(unthanked);
  });
});
