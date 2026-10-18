---
"@honeybbq/teamspeak": patch
---

`instanceof` across two copies of the package no longer depends on class names, which a consumer's minifier renames (`esbuild --minify` made every cross-copy check false). Each error class declares a fixed static `kind`, an instance carries its class's kinds and those it extends under the `Symbol.for("@honeybbq/teamspeak.error")` brand, and `instanceof` compares kinds, so a `ServerError` from one copy is still a `TeamspeakError` of the other.
