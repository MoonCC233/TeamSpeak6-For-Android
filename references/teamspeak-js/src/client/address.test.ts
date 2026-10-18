import { describe, expect, it } from "vitest";
import { isIpAddress, joinHostPort, splitHostPort } from "./address.js";

describe("TeamSpeak address helpers", () => {
  it("keeps bracketed IPv6 normalized when adding a port", () => {
    expect(splitHostPort("[2001:db8::1]:9987")).toEqual({ host: "2001:db8::1", port: "9987" });
    expect(joinHostPort("[2001:db8::1]", "9987")).toBe("[2001:db8::1]:9987");
    expect(isIpAddress("[2001:db8::1]")).toBe(true);
  });

  it("does not treat the final IPv6 segment as a port", () => {
    expect(splitHostPort("2001:db8::1")).toEqual({ host: "2001:db8::1", port: "9987" });
  });

  it("keeps ordinary host and port parsing unchanged", () => {
    expect(splitHostPort("voice.example.com:9988")).toEqual({
      host: "voice.example.com",
      port: "9988",
    });
    expect(joinHostPort("voice.example.com", "9988")).toBe("voice.example.com:9988");
  });
});

describe("connecting to an address that cannot be one", () => {
  it("rejects with an InvalidAddressError and leaves the client disconnected", async () => {
    const { Client } = await import("./client.js");
    const { generateIdentitySync } = await import("./crypto/identity.js");
    const { InvalidAddressError } = await import("./errors.js");
    const { FakeSocket } = await import("./testing/fake-server.js");
    const identity = generateIdentitySync(0);
    for (const address of [
      "127.0.0.1:70000",
      "127.0.0.1:0",
      "[::1]:99999",
      "",
      "  ",
      "bad host:9987",
      "127.0.0.1:abc",
    ]) {
      let sockets = 0;
      const client = new Client(identity, address, "Bot", {
        resolver: { resolve: async () => [] },
        socketFactory: () => {
          sockets++;
          return new FakeSocket();
        },
      });
      const events: unknown[] = [];
      client.on("disconnected", (e) => events.push(e));
      await expect(client.connect(), address).rejects.toBeInstanceOf(InvalidAddressError);
      expect(client.status).toBe("disconnected");
      expect(sockets).toBe(0);
      await new Promise((resolve) => setImmediate(resolve));
      expect(events).toEqual([]);
    }
  });

  it("wraps a failed lookup in a TeamspeakError with the cause", async () => {
    const { Client } = await import("./client.js");
    const { generateIdentitySync } = await import("./crypto/identity.js");
    const { TeamspeakError } = await import("@honeybbq/teamspeak");
    const client = new Client(generateIdentitySync(0), "no-such-host.invalid:9987", "Bot", {
      resolver: { resolve: async () => [] },
    });
    const error: unknown = await client.connect().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(TeamspeakError);
    expect((error as Error).cause).toMatchObject({ code: "ENOTFOUND" });
  });
});
