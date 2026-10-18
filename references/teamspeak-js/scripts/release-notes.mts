/**
 * Prints the body of the GitHub release for a tag: an install line, what
 * changed, and a link to the changes since the previous v* tag.
 *
 *   node scripts/release-notes.mts v1.0.0-beta.3 > release-notes.md
 *
 * The changes come from the changelog changesets writes for
 * @honeybbq/teamspeak, whose entries are used as written.
 *
 * Fails when the changelog has no section for the version. A tag without a
 * changelog entry is a mistake, and the release should stop on it.
 */

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repo = "HoneyBBQ/teamspeak-js";

/** One changelog section: the entries of each group, in the changelog's order. */
export type Section = Map<string, string[]>;

/**
 * Reads the section of a changelog for a version.
 *
 * An entry is a top-level list item and whatever is indented under it, kept
 * verbatim, links and thanks included. "Updated dependencies" entries, which
 * only name another package's version, are dropped. Throws when the changelog
 * has no section for the version, or the section has no entries.
 */
export function readSection(changelog: string, version: string, name: string): Section {
  const lines = changelog.split(/\r?\n/);
  const start = lines.findIndex((line) => line === `## ${version}`);
  if (start < 0) {
    throw new Error(`${name}: CHANGELOG.md has no section "## ${version}"`);
  }

  const section: Section = new Map();
  let group: string | undefined;
  let entry: string[] | undefined;

  const finish = () => {
    if (group !== undefined && entry !== undefined) {
      while (entry.length > 0 && entry[entry.length - 1] === "") entry.pop();
      const text = entry.join("\n");
      if (!text.startsWith("- Updated dependencies")) {
        const list = section.get(group) ?? [];
        list.push(text);
        section.set(group, list);
      }
    }
    entry = undefined;
  };

  for (const line of lines.slice(start + 1)) {
    if (line.startsWith("## ")) break;
    if (line.startsWith("### ")) {
      finish();
      group = line.slice(4).trim();
    } else if (line.startsWith("- ")) {
      finish();
      entry = [line];
    } else if (entry !== undefined) {
      entry.push(line);
    }
  }
  finish();

  if (section.size === 0) {
    throw new Error(`${name}: the section "## ${version}" of CHANGELOG.md has no entries`);
  }
  return section;
}

/** The release body for a version, without the compare link. */
export function renderBody(version: string, section: Section): string {
  const parts = [`Install:\n\n\`\`\`sh\nnpm install @honeybbq/teamspeak@${version}\n\`\`\``];
  for (const [group, entries] of section) {
    // A multi-paragraph entry keeps its blank lines, so entries are separated
    // by a line of their own only when one of them has paragraphs.
    const loose = entries.some((e) => e.includes("\n\n"));
    parts.push(`### ${group}\n\n${entries.join(loose ? "\n\n" : "\n")}`);
  }
  return parts.join("\n\n");
}

/** The tag before this one by version order, with prereleases before their release. */
function previousTag(tag: string): string | undefined {
  const tags = execFileSync(
    "git",
    ["-c", "versionsort.suffix=-", "tag", "--list", "v*", "--sort=-v:refname"],
    { encoding: "utf8" },
  )
    .split("\n")
    .filter(Boolean);
  const at = tags.indexOf(tag);
  return at < 0 ? tags[0] : tags[at + 1];
}

function main(argv: string[]): void {
  const tag = argv[0];
  if (argv.length !== 1 || !tag?.startsWith("v")) {
    console.error("usage: node scripts/release-notes.mts v<version>");
    process.exit(2);
  }
  const version = tag.slice(1);
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

  let body: string;
  try {
    const changelog = readFileSync(resolve(root, "CHANGELOG.md"), "utf8");
    body = renderBody(version, readSection(changelog, version, "@honeybbq/teamspeak"));
  } catch (error) {
    console.error(`release-notes: ${error instanceof Error ? error.message : String(error)}`);
    process.exit(1);
  }

  const previous = previousTag(tag);
  const compare = previous
    ? `\n\n**Full changelog**: https://github.com/${repo}/compare/${previous}...${tag}`
    : "";
  process.stdout.write(`${body}${compare}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2));
}
