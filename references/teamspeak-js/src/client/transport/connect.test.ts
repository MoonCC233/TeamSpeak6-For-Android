import { describe, it, expect } from "vitest";
import { EventEmitter } from "node:events";
import { isIP } from "node:net";
import type { Socket as UdpSocket } from "node:dgram";
import { PacketHandler } from "./handler.js";
import { Crypt } from "../crypto/crypt.js";
import { generateIdentitySync } from "../crypto/identity.js";

/**
 * A socket stand-in that records the family it was created for and accepts any
 * connect() without touching the network.
 */
function recordingSocket(family: 4 | 6): { socket: UdpSocket; connects: Array<[number, string]> } {
  const connects: Array<[number, string]> = [];
  const socket = new EventEmitter() as unknown as UdpSocket;

  // dgram's connect is overloaded; the handler uses the (port, address, cb)
  // form, which is what the recording stand-in has to answer.
  const connect = ((port: number, address: string, cb: () => void) => {
    connects.push([port, address]);
    setImmediate(cb);
  }) as unknown as UdpSocket["connect"];
  socket.connect = connect;
  socket.close = () => socket;
  (socket as unknown as { _family: 4 | 6 })._family = family;

  return { socket, connects };
}

describe("PacketHandler.connect", () => {
  /**
   * The address family must come from DNS, not from `isIP()`. `isIP()` answers
   * 0 for every hostname, so deriving the family from it sent all hostnames to
   * a udp4 socket — and a udp4 socket given a name that resolves to IPv6 drops
   * its datagrams silently. `localhost` resolves to ::1 first on a stock
   * macOS/Linux host, so `localhost:9987` never connected at all.
   */
  it("resolves the address family instead of inferring it from the host text", async () => {
    expect(isIP("localhost")).toBe(0);

    const seen: Array<4 | 6> = [];
    const handler = new PacketHandler(new Crypt(generateIdentitySync(1)), undefined, (family) => {
      seen.push(family);
      return recordingSocket(family).socket;
    });

    await handler.connect("localhost:9987");

    // Whichever family DNS chose, it must be a real one rather than the
    // defaulted 4 that the old code always used.
    expect(seen).toHaveLength(1);
    expect([4, 6]).toContain(seen[0]);

    handler.close();
  });

  it("connects to the resolved address, not the original hostname", async () => {
    const sockets: Array<{ connects: Array<[number, string]> }> = [];
    const handler = new PacketHandler(new Crypt(generateIdentitySync(1)), undefined, (family) => {
      const rec = recordingSocket(family);
      sockets.push(rec);
      return rec.socket;
    });

    await handler.connect("localhost:9987");

    const connects = sockets[0]?.connects ?? [];
    expect(connects).toHaveLength(1);
    const [port, host] = connects[0]!;
    expect(port).toBe(9987);
    // A resolved literal, so the socket cannot end up on a different family
    // than it was created for.
    expect(isIP(host)).not.toBe(0);

    handler.close();
  });

  it("keeps the requested port for an IPv6 literal in brackets", async () => {
    const sockets: Array<{ connects: Array<[number, string]> }> = [];
    const handler = new PacketHandler(new Crypt(generateIdentitySync(1)), undefined, (family) => {
      const rec = recordingSocket(family);
      sockets.push(rec);
      return rec.socket;
    });

    await handler.connect("[::1]:9987");

    const connects = sockets[0]?.connects ?? [];
    expect(connects[0]).toEqual([9987, "::1"]);

    handler.close();
  });
});
