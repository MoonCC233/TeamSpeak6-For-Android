#!/usr/bin/env node
/**
 * B2s local demo/verification server for the TS6 Pad shell.
 *
 * It does three things without any TeamSpeak/WebSpeak backend:
 *   1. Serves the built WebSpeak web UI (pad/web/dist) with a mock
 *      /api/public-config so the real UI can render inside the WebView.
 *   2. Serves capability.html, a self-contained WebRTC/WebAudio/WebSocket
 *      probe for the Android WebView.
 *   3. Provides /ws/echo for the probe and a silent /ws/voice placeholder.
 *
 * The real WebSpeak gateway is required for actual voice and screen sharing;
 * this server exists so B2s (WebView compatibility) can be verified offline.
 *
 * Usage: node tools/demo-server.mjs [port]
 * License: AGPL-3.0-only (same as pad/).
 */
import { createServer } from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { networkInterfaces } from "node:os";
import { createHash } from "node:crypto";

const here = fileURLToPath(new URL(".", import.meta.url));
const webDist = resolve(here, "..", "..", "web", "dist");
const port = Number(process.argv[2] || process.env.PORT || 8080);

const mimeTypes = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".wasm": "application/wasm",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".ico": "image/x-icon",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
    ".txt": "text/plain; charset=utf-8",
};

function json(response, status, body) {
    response.writeHead(status, {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
    });
    response.end(JSON.stringify(body));
}

function serveFile(response, filePath) {
    if (!existsSync(filePath) || !statSync(filePath).isFile()) {
        return false;
    }
    const type = mimeTypes[extname(filePath).toLowerCase()] || "application/octet-stream";
    response.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    createReadStream(filePath).pipe(response);
    return true;
}

// Minimal WebSocket server (text frames only) so the demo has no npm
// dependency on `ws`. The capability probe sends one short text frame and
// expects an echo; WebSpeak's /ws/voice messages are only logged here.
const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

function websocketAccept(key) {
    return createHash("sha1").update(key + WS_GUID).digest("base64");
}

function websocketFrame(text) {
    const payload = Buffer.from(text, "utf8");
    let header;
    if (payload.length < 126) {
        header = Buffer.from([0x81, payload.length]);
    } else if (payload.length < 65536) {
        header = Buffer.alloc(4);
        header[0] = 0x81;
        header[1] = 126;
        header.writeUInt16BE(payload.length, 2);
    } else {
        header = Buffer.alloc(10);
        header[0] = 0x81;
        header[1] = 127;
        header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    return Buffer.concat([header, payload]);
}

function readWebSocketFrames(socket, onText) {
    let pending = Buffer.alloc(0);
    socket.on("data", (chunk) => {
        pending = Buffer.concat([pending, chunk]);
        while (pending.length >= 2) {
            const opcode = pending[0] & 0x0f;
            const masked = (pending[1] & 0x80) !== 0;
            let length = pending[1] & 0x7f;
            let offset = 2;
            if (length === 126) {
                if (pending.length < 4) return;
                length = pending.readUInt16BE(2);
                offset = 4;
            } else if (length === 127) {
                if (pending.length < 10) return;
                length = Number(pending.readBigUInt64BE(2));
                offset = 10;
            }
            const maskStart = offset;
            if (masked) {
                offset += 4;
            }
            if (pending.length < offset + length) {
                return;
            }
            const mask = masked ? pending.subarray(maskStart, maskStart + 4) : null;
            const payload = Buffer.from(pending.subarray(offset, offset + length));
            if (mask) {
                for (let index = 0; index < payload.length; index += 1) {
                    payload[index] ^= mask[index % 4];
                }
            }
            pending = pending.subarray(offset + length);
            if (opcode === 0x8) {
                socket.end(Buffer.from([0x88, 0x00]));
                return;
            }
            if (opcode === 0x9) {
                socket.write(Buffer.from([0x8a, 0x00]));
                continue;
            }
            if (opcode === 0x1) {
                onText(payload.toString("utf8"));
            }
            // Binary/continuation frames are ignored by this offline demo.
        }
    });
}

const server = createServer((request, response) => {
    const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
    const pathname = decodeURIComponent(url.pathname);

    if (pathname === "/api/health" || pathname === "/health") {
        return json(response, 200, { status: "ok", version: "0.2.8-demo" });
    }

    // The real gateway reads this from its admin database. The demo advertises
    // the user's own TeamSpeak server so the join form has a realistic target.
    if (pathname === "/api/public-config") {
        return json(response, 200, {
            version: "0.2.8",
            initialized: true,
            mobile: false,
            siteName: "TS6 Pad 本地演示",
            accessMode: "fixed",
            target: "ts.mooncc.cn#9987",
            targetPrefillBlocked: false,
            welcomeText: "本地 WebView 兼容性演示（未连接真实网关）",
            welcomeTextEn: "Local WebView compatibility demo (no real gateway)",
            accelerationRelays: [],
        });
    }

    if (pathname === "/api/skins") {
        return json(response, 200, { skins: [], defaultSkinId: "builtin.light" });
    }

    if (pathname === "/api/join-ticket") {
        return json(response, 503, {
            ok: false,
            code: "DEMO_MODE",
            message: "本地演示服务器没有连接真实 WebSpeak 网关；请改用自建实例验证语音。",
        });
    }

    if (pathname.startsWith("/api/")) {
        return json(response, 404, { ok: false, code: "NOT_FOUND" });
    }

    if (pathname === "/capability" || pathname === "/capability/" || pathname === "/capability.html") {
        return serveFile(response, join(here, "capability.html"));
    }

    if (pathname === "/" || pathname === "") {
        return serveFile(response, join(webDist, "index.html"));
    }

    const safePath = normalize(pathname).replace(/^([/\\])+/, "");
    const candidate = join(webDist, safePath);
    if (candidate.startsWith(webDist) && serveFile(response, candidate)) {
        return;
    }

    // SPA fallback for routes such as /admin.
    if (serveFile(response, join(webDist, "index.html"))) {
        return;
    }

    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("Not found. Build pad/web first (npm --prefix web run build).");
});

server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url || "/", `http://${request.headers.host || "localhost"}`);
    const key = request.headers["sec-websocket-key"];
    if (!key || (url.pathname !== "/ws/echo" && url.pathname !== "/ws/voice")) {
        socket.destroy();
        return;
    }

    socket.setNoDelay(true);
    socket.on("error", () => {});
    socket.write(
        "HTTP/1.1 101 Switching Protocols\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + `Sec-WebSocket-Accept: ${websocketAccept(key)}\r\n\r\n`,
    );

    if (url.pathname === "/ws/echo") {
        readWebSocketFrames(socket, (text) => socket.write(websocketFrame(text)));
        socket.write(websocketFrame(JSON.stringify({ type: "hello", server: "ts6pad-demo" })));
    } else {
        console.log("[demo] /ws/voice placeholder accepted a client (no real gateway)");
        readWebSocketFrames(socket, (text) => {
            console.log("[demo] /ws/voice message:", text.slice(0, 200));
        });
    }

    if (head.length > 0) {
        socket.emit("data", head);
    }
});

server.listen(port, "0.0.0.0", () => {
    const addresses = [ `http://localhost:${port}/` ];
    for (const entries of Object.values(networkInterfaces())) {
        for (const entry of entries || []) {
            if (entry.family === "IPv4" && !entry.internal) {
                addresses.push(`http://${entry.address}:${port}/`);
            }
        }
    }
    console.log("[demo] TS6 Pad B2s demo server");
    console.log("[demo] WebSpeak UI : " + addresses[0]);
    console.log("[demo] Capability    : " + addresses[0] + "capability.html");
    for (const address of addresses.slice(1)) {
        console.log("[demo] LAN           : " + address);
    }
    console.log("[demo] adb reverse tcp:" + port + " tcp:" + port + "  -> use http://localhost:" + port + " in the Pad shell");
});
