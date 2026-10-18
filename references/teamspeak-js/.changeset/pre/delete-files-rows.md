---
"@honeybbq/teamspeak": patch
---

`deleteFile` with several paths now works: the names were sent as one `name` parameter holding `/a|/b`, a single path the server refused; they are rows of the command (`name=/a|name=/b`), as TS3 and TS6 expect. A `CommandRequest` can carry such rows in its new `rows` field.
