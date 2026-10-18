---
"@honeybbq/teamspeak": major
---

- Supplying `reconnect` turns reconnecting on; `ReconnectOptions.enabled` is gone.
- `ClientOptions`, `ReconnectOptions` and `BackoffPolicy` fields are `readonly`, and every optional one accepts `undefined`, so `serverPassword: process.env.X` compiles under `exactOptionalPropertyTypes` (the README's Quick Start did not). An undefined `backoff` field keeps its default.
- `AddrResolver.resolve(addr, { signal })` takes the signal in an options object and may return a readonly array; `ResolvedAddr` is readonly, with `source` and `expiry` optional.
- `defaultShouldRetry(error)` takes only the error.
