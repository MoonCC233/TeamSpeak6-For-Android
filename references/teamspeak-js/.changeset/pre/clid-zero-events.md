---
"@honeybbq/teamspeak": patch
---

`clientEnter`, `clientLeave` and `clientMoved` are no longer emitted for a row with client id 0, or with none: no client has that id. The raw `notification` event still carries the row.
