---
"@honeybbq/teamspeak": patch
---

A parameter named `__proto__` was lost when a line was parsed, because the parameters went into a plain object, whose `__proto__` setter swallowed it. They now go into an object with no prototype, so every key the server sends is kept as sent.
