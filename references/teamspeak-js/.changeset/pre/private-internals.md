---
"@honeybbq/teamspeak": patch
---

A `Client` keeps its connection state in private fields: `util.inspect(client)` and `JSON.stringify(client)` used to include the session's IV and signature, the handler and the plain-text passwords, through members that were hidden from the types but not at runtime. The server and default channel passwords are hashed once, when the client is made, and held only in that form. The diagnostics messages' `client` is typed `Client`, and a command that times out is named, never quoted.
