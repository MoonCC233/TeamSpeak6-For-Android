---
"@honeybbq/teamspeak": patch
---

Address resolution has ten seconds in all, and the client holds a custom `resolver` to it too: the `signal` the resolver is given aborts once the budget is spent, and the client connects to the address as given. A custom resolver used to be bounded by nothing but the caller's signal, and the built-in one had eight seconds.
