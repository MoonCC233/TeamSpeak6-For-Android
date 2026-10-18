---
"@honeybbq/teamspeak": major
---

- `EAXTagMismatchError` and `FakeSignatureMismatchError` are no longer exported: they never reached a caller. `CryptoInitError` is now thrown, for a handshake that fails on what the server sent.
- `TeamspeakError` and every subclass answer `instanceof` across two installed copies of the package, matched by the kinds each instance carries under a shared `Symbol.for("@honeybbq/teamspeak.error")` brand: every class declares a fixed static `kind`, so the match survives a minifier that renames classes.
- `getClientInfo` throws a `TeamspeakError`, not a plain `Error`, when the server returns no data; a whisper with no or too many targets throws a `RangeError`.
- `ErrorCode`, `Reason`, `ClientStatus`, every enumeration, `noopLogger` and `consoleLogger` are frozen, as `defaultBackoffPolicy` already was: `ErrorCode.OK = 5` used to stick, for every caller in the process.
