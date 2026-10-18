import { describe, it, expect } from "vitest";
import { isAutoNicknameMatch } from "./helpers.js";

describe("isAutoNicknameMatch", () => {
  it("exact match", () => {
    expect(isAutoNicknameMatch("Alice", "Alice")).toBe(true);
  });

  it("match with numeric suffix", () => {
    expect(isAutoNicknameMatch("Alice", "Alice123")).toBe(true);
    expect(isAutoNicknameMatch("Alice", "Alice1")).toBe(true);
  });

  it("no match with non-numeric suffix", () => {
    expect(isAutoNicknameMatch("Alice", "AliceX")).toBe(false);
    expect(isAutoNicknameMatch("Alice", "Bob")).toBe(false);
  });
});
