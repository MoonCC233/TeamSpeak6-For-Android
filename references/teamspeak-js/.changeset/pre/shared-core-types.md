---
"@honeybbq/teamspeak": major
---

The types every client shares moved from `@honeybbq/teamspeak/client` to `@honeybbq/teamspeak`, so a second client can use them without a second home for each. For anyone on a beta, import each of these from `@honeybbq/teamspeak`:

- `ClientStatus`, `Unsubscribe`, `Listener`, `ConnectedEvent`, `DisconnectedEvent` and `ServerFamily`.
- `ReconnectOptions`, `BackoffPolicy`, `defaultBackoffPolicy` and `backoffDelay`. `defaultShouldRetry` stays in `@honeybbq/teamspeak/client`.
- `AlreadyConnectedError`.

`Listener` now takes the event map as well: `Listener<EventMap, "textMessage">`. `client.on` infers payloads as before. `EventMap` stays in the client and extends the new `ServerEventMap` and `SessionEventMap`, which the core exports; it has the same keys and payloads.
