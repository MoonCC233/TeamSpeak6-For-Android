---
"@honeybbq/teamspeak": patch
---

`listPermissions` gave every permission the id 0 over the voice protocol, whose `notifypermissionlist` sends no `permid`. There a permission's id is its place in the list, counting from 1 — the `failed_permid` of refused commands confirms it on TeamSpeak 3 and 6 — and `id` is now that. Over ServerQuery the id the server sends is used.
