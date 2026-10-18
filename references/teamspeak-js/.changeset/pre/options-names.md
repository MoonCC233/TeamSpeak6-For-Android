---
"@honeybbq/teamspeak": major
---

Renamed for consistency: `ClientDatabasePage` is now `ClientDatabasePageOptions`, `ChannelGroupClientFilter` is `ChannelGroupClientListOptions`, and `PageOptions` is `PagingOptions`, so every options type ends in `Options`. `TimedOutError` is now `ConnectionTimeoutError`, which says which timeout it is, apart from a command's `CommandTimeoutError`.
