# Changesets

Every change a user would notice gets a changeset: `pnpm changeset`, pick the
package and the bump, describe it for the changelog. `pnpm changeset version`
turns them into version bumps and CHANGELOG entries.

Write the changeset for the person upgrading. A change that breaks an import or
a call says what to write instead.

`@honeybbq/teamspeak` is the only package, so a changeset names it.

Entries link to their pull request and commit, which needs a token with read
access to GitHub: `pnpm version-packages` takes it from `gh auth token`, or
from `GITHUB_TOKEN` when that is set.

The repository is in prerelease mode (`pre.json`, tag `beta`): `pnpm changeset
version` makes `1.0.0-beta.N` versions until `pnpm changeset pre exit` ends it.
