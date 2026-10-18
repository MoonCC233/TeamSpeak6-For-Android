---
"@honeybbq/teamspeak": minor
---

`PermissionName` names every permission TeamSpeak 3.13 and 6 servers list, for `permissionByName`: `permissionByName(PermissionName.ClientTalkPower)`. It is a frozen table and a union type of its values, like `ErrorCode`; `permissionByName` still takes any string, so a permission the table lacks works as before. Seven exist only on TeamSpeak 6 and say so.
