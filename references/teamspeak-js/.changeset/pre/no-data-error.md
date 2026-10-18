---
"@honeybbq/teamspeak": major
---

A command that exists to return data throws the new `NoDataError` (a `TeamspeakError` with its own kind, carrying the `command`) when the server answers without it: `createChannel`, `createServerGroup`, `copyServerGroup`, `createChannelGroup`, `copyChannelGroup`, `addPrivilegeKey`, `initFileUpload`, `initFileDownload`, `getFileInfo`, `getChannelInfo`, `getClientInfo`, `getClientUIDByID`, `getClientDBIDByUID`, `getClientNameByDBID`, `getClientNameByUID`, `getClientDatabaseInfo`, `getMessage`, `viewLog`, `getServerInfo`, `getHostInfo`, `getInstanceInfo`, `getVersion`, `whoAmI`, `getConnectionInfo` and `getServerIDByPort`. They used to return an id of 0 or an object of empty fields, which read like a real answer; `getClientInfo` threw a plain `TeamspeakError`.
