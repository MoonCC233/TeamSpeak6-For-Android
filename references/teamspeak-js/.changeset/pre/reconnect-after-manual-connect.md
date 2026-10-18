---
"@honeybbq/teamspeak": patch
---

The reconnect loop ends when the client is connected, or connecting, by other means — a `connect()` of your own during the backoff — instead of carrying on beside the new session. `defaultShouldRetry` now treats an `AlreadyConnectedError` as a reason to stop.
