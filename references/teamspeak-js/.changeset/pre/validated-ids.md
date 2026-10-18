---
"@honeybbq/teamspeak": major
---

The branded id constructors validate: `asClientID`, `asClientFileTransferID` and `asServerFileTransferID` take an integer from 0 to 65535, `asPermissionID` one from 0 to 2^32−1, `asIconID` one from −2^31 to 2^32−1, and the bigint ids — `asChannelID`, `asClientDBID`, `asServerGroupID`, `asChannelGroupID`, `asBanID`, `asMessageID`, `asServerID` — one from 0 to 2^64−1; anything else throws a `RangeError`. They used to brand whatever they were given. Ids read from the server never throw: one outside its range reads as 0.
