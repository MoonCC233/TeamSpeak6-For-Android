---
"@honeybbq/teamspeak": patch
---

The client answers at most one Init1 RSA challenge per connection attempt. A repeat of the same challenge, which means the answer was lost, gets the same answer again without solving anything; a different challenge gets none. A server could otherwise keep the event loop busy with challenge after challenge, each up to 100000 synchronous squarings.
