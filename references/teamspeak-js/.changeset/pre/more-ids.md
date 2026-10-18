---
"@honeybbq/teamspeak": major
---

New branded ids: `BanID`, `MessageID`, `ServerID`, `ClientFileTransferID` and `ServerFileTransferID`, with `asBanID` and the like. They type `BanEntry.id`, `addBan`, `banClient`, `deleteBan`, `OfflineMessage.id`, `getMessage`, `deleteMessage`, `updateMessageFlag`, `WhoAmI.serverID`, `getServerIDByPort`, the transfer ids of `FileUploadInfo`, `FileDownloadInfo` and `ActiveTransfer`, and `stopFileTransfer`. `PermissionLocation` (and so `PermissionOverviewEntry`) is a union discriminated on `target`, with `serverGroupID`, `databaseID`, `channelID` and `channelGroupID` where `id1` and `id2` were; a row with a target `PermissionTarget` does not name is left out. `ServerError.failedPermissionId` is `failedPermissionID`, a `PermissionID`. `ServerGroup.sortID` and `ChannelGroup.sortID` are numbers.
