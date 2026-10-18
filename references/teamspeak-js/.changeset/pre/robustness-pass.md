---
"@honeybbq/teamspeak": patch
---

File transfers, cancellation and a few error details now behave the way the platform's own APIs do:

- A file transfer that moves no byte for `idleTimeoutMs` (30 seconds unless set; 0 turns it off) fails with a `FileTransferError`. Before, a server that went silent held it until the caller's signal ended it. Writing the transfer key now honours the signal and the dial timeout too.
- A download that sends more than the size the server announced fails after that size, without writing the excess, and one that ends short of it fails. Both used to succeed.
- `client.events()` rejects with the signal's reason when the signal aborts, as Node's `events.on` does, instead of ending quietly. Calling `next()` again before the previous call settles no longer loses a call. The iterator also stops listening when a disconnect ends it, not only when the loop calls `return()`.
- SRV records are tried in the order RFC 2782 gives: by priority, then by weight. Before, the order was the one the DNS answer arrived in.
- `Resolver.resolve("")` throws an `InvalidAddressError` rather than a plain `Error`.
- An error's `name` is no longer an own enumerable property, so `JSON.stringify(err)` and `Object.keys(err)` treat it as they treat a built-in error's.
- Timeouts and the flood throttle measure time with a monotonic clock, so a change of the system clock cannot stall or burst them.
