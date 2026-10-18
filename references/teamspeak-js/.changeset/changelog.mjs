import github from "@changesets/changelog-github";

// A release line starts with its links and ends them with the credit:
// "- [#1](url) [`abc1234`](url) Thanks [@a](url), [@b](url)! - Summary".
const credit =
  /^(\n*-(?: \[[^\]]+\]\([^)]+\))*) Thanks (\[@[^\]]+\]\([^)]+\)(?:, \[@[^\]]+\]\([^)]+\))*)!(?= - )/;

const search = `query($q: String!) {
  search(query: $q, type: ISSUE, first: 100) {
    nodes { ... on PullRequest { number mergedAt } }
  }
}`;

/**
 * Whether the user's first merged pull request is `pull`, or, for an entry
 * without a pull request, whether none was merged before `commit`. Throws when
 * GitHub cannot be asked.
 *
 * @param {string} login
 * @param {{ repo: string, pull?: number, commit?: string }} entry
 * @returns {Promise<boolean>}
 */
async function isFirstContribution(login, { repo, pull, commit }) {
  const token = process.env.GITHUB_TOKEN;
  if (!token) throw new Error("GITHUB_TOKEN is not set");
  const headers = { Authorization: `Bearer ${token}`, "User-Agent": "teamspeak-js-changelog" };

  const q = `repo:${repo} is:pr is:merged author:${login}`;
  const response = await fetch("https://api.github.com/graphql", {
    method: "POST",
    headers,
    body: JSON.stringify({ query: search, variables: { q } }),
  });
  const { data } = await response.json();
  const merged = data.search.nodes
    .filter((node) => node.number !== undefined)
    .sort((a, b) => Date.parse(a.mergedAt) - Date.parse(b.mergedAt));
  if (pull !== undefined) return merged[0]?.number === pull;

  const found = await fetch(`https://api.github.com/repos/${repo}/commits/${commit}`, { headers });
  const { commit: info } = await found.json();
  return merged.length === 0 || Date.parse(merged[0].mergedAt) >= Date.parse(info.committer.date);
}

/**
 * Builds a formatter for `@changesets/changelog-github` entries that thanks
 * only first-time contributors. Everyone else, the `maintainers` of the options
 * and bots included, loses the "Thanks" and the entry keeps its links.
 *
 * `isFirst(login, { repo, pull, commit })` decides who is new, once per login
 * and entry.
 * When it throws, as it does offline or without a GITHUB_TOKEN, nobody is
 * thanked: a changelog without thanks beats a `version-packages` that fails.
 *
 * @param {typeof isFirstContribution} isFirst
 * @returns {import("@changesets/types").ChangelogFunctions}
 */
export function createChangelog(isFirst) {
  /** @type {Map<string, Promise<boolean>>} */
  const known = new Map();

  return {
    getDependencyReleaseLine: github.getDependencyReleaseLine,
    async getReleaseLine(changeset, type, options) {
      const line = await github.getReleaseLine(changeset, type, options);
      const found = credit.exec(line);
      if (!found) return line;

      const [thanks, links, users] = found;
      const repo = options.repo;
      const pull = /\/pull\/(\d+)\)/.exec(links)?.[1];
      const commit = /\/commit\/([0-9a-f]+)\)/.exec(links)?.[1];
      const maintainers = (options.maintainers ?? []).map((name) => name.toLowerCase());

      const thanked = [];
      for (const user of users.split(", ")) {
        const login = user.slice(2, user.indexOf("]"));
        if (maintainers.includes(login.toLowerCase())) continue;
        if (login.endsWith("[bot]") || user.includes("](https://github.com/apps/")) continue;

        const key = `${login} ${pull ?? commit}`;
        if (!known.has(key)) {
          const entry = { repo, pull: pull && Number(pull), commit };
          known.set(
            key,
            isFirst(login, entry).catch(() => false),
          );
        }
        if (await known.get(key)) thanked.push(user);
      }
      const rest = thanked.length > 0 ? ` Thanks ${thanked.join(", ")}!` : "";
      return line.replace(thanks, `${links}${rest}`);
    },
  };
}

// changesets loads a changelog formatter from its default export.
// oxlint-disable-next-line import/no-default-export
export default createChangelog(isFirstContribution);
