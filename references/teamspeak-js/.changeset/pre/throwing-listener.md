---
"@honeybbq/teamspeak": patch
---

A listener that throws no longer stops the listeners after it from getting the same event. Each listener runs on its own; an exception is rethrown from a microtask, so it still surfaces as an `uncaughtException`, as an EventEmitter listener's would.
