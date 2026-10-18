---
"@honeybbq/teamspeak": patch
---

The command throttle releases waiting commands in the order they arrived. Each waiter used to poll for a token on its own timer, so a command sent later could take the turn of one that had waited longer, and commands went out of order.
