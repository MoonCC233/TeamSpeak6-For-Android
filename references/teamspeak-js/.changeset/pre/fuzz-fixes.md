---
"@honeybbq/teamspeak": patch
---

A line of rows that each named a field of their own made the rows grow with the square of the line: TeamSpeak leaves out of a row the fields unchanged from the one before, so every row took on all the fields of all the rows before it, and one line of a few hundred kilobytes from a server, or from anyone who could forge its first packets, expanded to gigabytes. The rows of a line are now held to a size in proportion to the line itself; past it, rows carry only the fields they were sent with. The rows of a real server's line never come near it.

After the 16-bit packet id wrapped, a command the server sent again because its acknowledgement was lost, with an id just behind the receive window, was taken for one far ahead of it and dropped unanswered, so the server went on sending it. It is now recognised as already delivered and acknowledged again.

`parseUint64`, and with it the channel id of `notifyclientmoved`, accepted a sign, a hex prefix and any number of digits. A `ctid` of `-1` made the handler throw a `RangeError`, which dropped the other rows of the same datagram and could fail a connection still being set up. Only plain decimal digits from 0 to 2^64-1 are read now; anything else reads as 0, as it does everywhere else.
