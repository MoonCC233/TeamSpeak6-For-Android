---
"@honeybbq/teamspeak": major
---

`ServerError` carries the server's code as a number, `code`, in place of the string `id`, along with `extraMessage` and `failedPermissionId`. The new `ErrorCode` table names every code (`err.code === ErrorCode.ChannelInvalidPassword`).

Reconnecting no longer retries a wrong server or channel password: the list of permanent refusals named the wrong codes (1029, 1030, 2569) and now names the real ones (1028, 781, 2568, plus the two bans). An `error` line without an `id` is now a refusal (`ErrorCode.Undefined`), not a success.
