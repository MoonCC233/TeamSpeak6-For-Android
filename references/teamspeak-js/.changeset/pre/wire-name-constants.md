---
"@honeybbq/teamspeak": minor
---

`CommandName`, `NotificationName` and `PropertyName` name what TeamSpeak 3.13 and 6 servers send on the wire, so a raw line or a property key no longer has to be spelled from memory: `client.exec(CommandName.ServerGroupList)`, `n.name === NotificationName.ChannelEdited`, `info[PropertyName.VirtualServerName]`. Each is a frozen table and a union type of its values, like `PermissionName`. `NotificationEvent.name` and `CommandRequest.command` accept these names with autocompletion and still take any string, since a server may send names the tables lack.
