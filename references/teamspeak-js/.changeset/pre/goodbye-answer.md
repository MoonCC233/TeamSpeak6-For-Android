---
"@honeybbq/teamspeak": patch
---

`disconnect()` waited a full second every time. It waited for an error line in answer to its goodbye, but TeamSpeak 3 and 6 answer `clientdisconnect` with a `notifyclientleftview` about the client itself and no error line. That announcement now ends the wait, so a disconnect takes a few milliseconds.
