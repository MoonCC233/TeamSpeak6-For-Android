/**
 * Minimal example: connect to a TeamSpeak server and print server info.
 *
 * Run:
 *   TEAMSPEAK_ADDR=127.0.0.1:9987 pnpm tsx examples/connect.ts
 *
 * The address may be a hostname, an IPv4 address, or a bracketed IPv6 literal.
 * A local server can be started with:
 *   docker run -d --name ts3 -e TS3SERVER_LICENSE=accept -p 9987:9987/udp teamspeak:latest
 */

import { Client, generateIdentitySync } from "@honeybbq/teamspeak/client";
import { listChannels, listClients } from "@honeybbq/teamspeak";

const ADDR = process.env["TEAMSPEAK_ADDR"] ?? "127.0.0.1:9987";

async function main(): Promise<void> {
  console.log(`[example] generating identity (level 8)…`);
  const identity = generateIdentitySync(8);
  console.log(`[example] identity UID: ${identity.publicKey().slice(0, 24)}…`);

  const client = new Client(identity, ADDR, "ts-js-example", {
    logger: {
      debug: () => {},
      info: (msg, ...a) => console.log("[INFO]", msg, ...a),
      warn: (msg, ...a) => console.warn("[WARN]", msg, ...a),
      error: (msg, ...a) => console.error("[ERROR]", msg, ...a),
    },
  });

  // Register event handlers before connecting
  client.on("connected", () => {
    console.log(`[example] connected! self clid=${client.clientID}`);
  });

  client.on("disconnected", ({ error, willReconnect }) => {
    if (error) console.error("[example] disconnected with error:", error.message);
    else console.log("[example] disconnected cleanly");
    if (willReconnect) console.log("[example] reconnecting");
  });

  client.on("clientEnter", (info) => {
    console.log(`[event] clientEnter  clid=${info.id} nick="${info.nickname}"`);
  });

  client.on("clientLeave", (evt) => {
    console.log(`[event] clientLeave  clid=${evt.clientID} reason=${evt.reason}`);
  });

  client.on("textMessage", (msg) => {
    console.log(
      `[event] textMessage  from="${msg.invokerName}" (${msg.invokerUID}): ${msg.message}`,
    );
  });

  console.log(`[example] connecting to ${ADDR}…`);
  await client.connect({ signal: AbortSignal.timeout(30_000) });
  console.log(`[example] handshake complete, clid=${client.clientID}`);

  // List channels
  try {
    // Every typed command takes { signal?, timeoutMs? } as its last argument.
    const channels = await listChannels(client, { timeoutMs: 5_000 });
    console.log(`\n[example] ${channels.length} channel(s):`);
    for (const ch of channels) {
      const indent = ch.parentID === 0n ? "  " : "    ";
      console.log(`${indent}[cid=${ch.id}] ${ch.name}`);
    }
  } catch (err) {
    console.warn("[example] channellist error (permission?):", (err as Error).message);
  }

  // List clients
  try {
    const clients = await listClients(client, { times: true, timeoutMs: 5_000 });
    console.log(`\n[example] ${clients.length} client(s) online:`);
    for (const cl of clients) {
      const marker = cl.id === client.clientID ? " ← (me)" : "";
      const idle = cl.idleTimeMs === undefined ? "" : ` idle ${Math.round(cl.idleTimeMs / 1000)}s`;
      console.log(`  [clid=${cl.id}] ${cl.nickname}${idle}${marker}`);
    }
  } catch (err) {
    console.warn("[example] clientlist error (permission?):", (err as Error).message);
  }

  // Stay connected for 10s to observe events, then disconnect
  console.log("\n[example] listening for events for 10s…");
  await new Promise<void>((resolve) => setTimeout(resolve, 10_000));

  console.log("[example] disconnecting…");
  await client.disconnect();
  console.log("[example] done.");
}

main().catch((err) => {
  console.error("[example] fatal:", err);
  process.exit(1);
});
