---
"@honeybbq/teamspeak": patch
---

`initserver` is taken only once crypto init has completed. One arriving before, which needs nothing but the public dummy key, used to resolve `connect()` as connected on a session without keys, so the next command — with any password hash it carried — went out under the dummy key.
