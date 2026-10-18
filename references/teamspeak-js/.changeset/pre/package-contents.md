---
"@honeybbq/teamspeak": patch
---

The packages ship their `LICENSE` (the client's had none) and their TypeScript sources, so source maps and declaration maps resolve to real files, and the JavaScript is no longer minified: class names such as `Client` and `ConnectionTimeoutError` read as themselves in stack traces and `util.inspect()`.
