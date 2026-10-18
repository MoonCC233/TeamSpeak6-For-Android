import { describe, it, expect } from "vitest";
import { defaultShouldRetry } from "./reconnect.js";
import { AlreadyConnectedError, ServerError } from "@honeybbq/teamspeak";
import { FileTransferError, InvalidAddressError } from "./errors.js";

describe("defaultShouldRetry", () => {
  // Retrying a ban makes it worse; retrying a dropped packet fixes it.
  it("treats a ban as permanent", () => {
    expect(defaultShouldRetry(new ServerError(3329, "connection failed, you are banned"))).toBe(
      false,
    );
    expect(defaultShouldRetry(new ServerError(3331, "flood ban"))).toBe(false);
  });

  // The codes once listed here were wrong: 1029 and 1030 are not the password
  // refusals, and 2569 is group modify power, so a wrong password was retried.
  it("treats the real password and permission refusals as permanent", () => {
    expect(defaultShouldRetry(new ServerError(1028, "invalid server password"))).toBe(false);
    expect(defaultShouldRetry(new ServerError(781, "invalid channel password"))).toBe(false);
    expect(defaultShouldRetry(new ServerError(2568, "insufficient client permissions"))).toBe(
      false,
    );
    expect(defaultShouldRetry(new ServerError(1029, "deployment active"))).toBe(true);
  });

  // The server also refuses transiently: a reconnect made while it still holds
  // the dropped session fails with 521 until it reaps the zombie. Giving up on
  // that would mean never recovering from an ordinary drop.
  it("keeps retrying an unrecognised server error", () => {
    expect(defaultShouldRetry(new ServerError(521, "too many clones already connected"))).toBe(
      true,
    );
    expect(defaultShouldRetry(new ServerError(9999, "something else"))).toBe(true);
  });

  it("treats a transport failure as worth retrying", () => {
    expect(defaultShouldRetry(new Error("read ECONNRESET"))).toBe(true);
    expect(defaultShouldRetry(new FileTransferError("connection timeout"))).toBe(true);
  });

  it("stops once the client is connected already", () => {
    expect(defaultShouldRetry(new AlreadyConnectedError())).toBe(false);
    expect(defaultShouldRetry(new InvalidAddressError("x:0", "bad port"))).toBe(false);
  });

  it("treats no error at all as worth retrying", () => {
    // A clean close with no error is a dropped socket, not a refusal.
    expect(defaultShouldRetry(undefined)).toBe(true);
  });
});
