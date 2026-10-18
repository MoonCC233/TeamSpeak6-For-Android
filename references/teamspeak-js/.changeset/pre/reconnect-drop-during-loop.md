---
"@honeybbq/teamspeak": patch
---

The reconnect loop no longer gives up its slot when the session it just reconnected drops before the loop resumes, as when the server finishes the handshake and stops in the same datagram. The loop used to log "reconnected" while the client was disconnected, and nothing retried; it now goes on retrying until its attempt is still connected when it resumes, with the drop's cause passed to `shouldRetry`.
