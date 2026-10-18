---
"@honeybbq/teamspeak": major
---

`initFileUpload` does not replace a file already at the path unless passed `overwrite: true`. It used to by default, so an upload to a name that was taken destroyed the file there.
