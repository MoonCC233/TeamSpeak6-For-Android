import { describe, expect, it } from "vitest";
import { readSection, renderBody } from "./release-notes.mts";

const changelog = `# @honeybbq/teamspeak

## 1.0.0-beta.2

### Major Changes

- [#12](https://github.com/HoneyBBQ/teamspeak-js/pull/12) [\`aaaaaaa\`](https://github.com/HoneyBBQ/teamspeak-js/commit/aaaaaaa) Thanks [@user](https://github.com/user)! - With a pull request.

  A second paragraph.

- [\`bbbbbbb\`](https://github.com/HoneyBBQ/teamspeak-js/commit/bbbbbbb) Thanks [@user](https://github.com/user)! - Commit only.

### Patch Changes

- Updated dependencies []:
  - @honeybbq/teamspeak@1.0.0-beta.2
- Plain entry.

## 1.0.0-beta.1

### Patch Changes

- Old.
`;

const withPR =
  "- [#12](https://github.com/HoneyBBQ/teamspeak-js/pull/12) [`aaaaaaa`](https://github.com/HoneyBBQ/teamspeak-js/commit/aaaaaaa) Thanks [@user](https://github.com/user)! - With a pull request.\n\n  A second paragraph.";
const commitOnly =
  "- [`bbbbbbb`](https://github.com/HoneyBBQ/teamspeak-js/commit/bbbbbbb) Thanks [@user](https://github.com/user)! - Commit only.";

describe("readSection", () => {
  it("keeps an entry with a pull request link verbatim, with its paragraphs", () => {
    const section = readSection(changelog, "1.0.0-beta.2", "pkg");
    expect(section.get("Major Changes")?.[0]).toBe(withPR);
  });

  it("keeps an entry with only a commit link verbatim", () => {
    const section = readSection(changelog, "1.0.0-beta.2", "pkg");
    expect(section.get("Major Changes")?.[1]).toBe(commitOnly);
  });

  it("stops at the next version, and drops the dependency-update entries", () => {
    const section = readSection(changelog, "1.0.0-beta.2", "pkg");
    expect(section.get("Patch Changes")).toEqual(["- Plain entry."]);
    const text = [...section.values()].flat().join("\n");
    expect(text).not.toContain("Updated dependencies");
    expect(text).not.toContain("Old.");
  });

  it("throws when the version has no section", () => {
    expect(() => readSection(changelog, "1.0.0-beta.9", "pkg")).toThrow(
      /no section "## 1.0.0-beta.9"/,
    );
  });

  it("throws when the section has no entries", () => {
    expect(() => readSection("## 1.0.0\n\n", "1.0.0", "pkg")).toThrow(/no entries/);
  });

  it("does not match a version that only starts the same", () => {
    expect(() => readSection(changelog, "1.0.0-beta", "pkg")).toThrow();
  });
});

describe("renderBody", () => {
  it("starts with the install line and lists the groups in order", () => {
    const body = renderBody("1.0.0-beta.2", readSection(changelog, "1.0.0-beta.2", "pkg"));
    expect(
      body.startsWith(
        "Install:\n\n```sh\nnpm install @honeybbq/teamspeak@1.0.0-beta.2\n```\n\n### Major Changes",
      ),
    ).toBe(true);
    expect(body.indexOf("### Major Changes")).toBeLessThan(body.indexOf("### Patch Changes"));
    expect(body).toContain(withPR);
    expect(body).not.toContain("Updated dependencies");
  });
});
