---
"@honeybbq/teamspeak": major
---

The command language's plumbing is no longer public API: `parseCommand`, `buildCommand`, `buildCommandOrdered` and the `Command` and `EscapedString` types are gone from the package entry. `escape` and `unescape` remain, for raw lines sent with `exec`, and `escape` returns `string`. The builders now escape parameter names as well as values.
