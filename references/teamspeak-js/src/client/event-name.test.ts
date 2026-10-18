import { describe, expect, expectTypeOf, it } from "vitest";
import type { TextMessageEvent } from "@honeybbq/teamspeak";
import { type Client, EventName, type EventMap } from "./index.js";

describe("EventName", () => {
  it("is frozen and names each event once", () => {
    expect(Object.isFrozen(EventName)).toBe(true);
    for (const [key, value] of Object.entries(EventName)) {
      expect(key.toLowerCase()).toBe(value.toLowerCase());
      expect(key[0]).toBe(key[0]!.toUpperCase());
      expect(value[0]).toBe(value[0]!.toLowerCase());
    }
    expect(new Set(Object.values(EventName)).size).toBe(Object.keys(EventName).length);
  });

  it("is exactly the keys of EventMap", () => {
    expectTypeOf<EventName>().toEqualTypeOf<keyof EventMap>();
  });

  it("types the listener's payload from the constant", () => {
    type OnText = typeof Client.prototype.on<typeof EventName.TextMessage>;
    type OnConnected = typeof Client.prototype.on<typeof EventName.Connected>;
    expectTypeOf<Parameters<OnText>[0]>().toEqualTypeOf<"textMessage">();
    expectTypeOf<Parameters<OnText>[1]>().toEqualTypeOf<(payload: TextMessageEvent) => void>();
    expectTypeOf<Parameters<OnConnected>[0]>().toEqualTypeOf<"connected">();
    expectTypeOf(EventName.TextMessage).toEqualTypeOf<"textMessage">();
  });
});
