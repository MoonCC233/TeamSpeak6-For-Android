import { describe, expect, it } from "vitest";
import { encodeRequest } from "./request.js";

describe("encodeRequest", () => {
  const request = {
    command: "clientmove",
    params: [
      ["clid", 7],
      ["cid", 42n],
      ["cpw", "hunter2"],
    ],
    passwords: ["cpw"],
  } as const;

  // The voice protocol hashes a channel password; ServerQuery sends it as is.
  // The request only says which parameter is a password.
  it("leaves password encoding to the transport", () => {
    expect(encodeRequest(request, (pw) => `hashed(${pw})`)).toBe(
      "clientmove clid=7 cid=42 cpw=hashed(hunter2)",
    );
    expect(encodeRequest(request, (pw) => pw)).toBe("clientmove clid=7 cid=42 cpw=hunter2");
  });

  it("sends an empty password as is, in every encoding", () => {
    const open = { ...request, params: [["cpw", ""]] as const };
    expect(encodeRequest(open, (pw) => `hashed(${pw})`)).toBe("clientmove cpw=");
  });

  it("omits unset parameters and sends flags bare", () => {
    expect(
      encodeRequest(
        {
          command: "clientlist",
          params: [
            ["-uid", true],
            ["-away", false],
            ["x", undefined],
          ],
        },
        (pw) => pw,
      ),
    ).toBe("clientlist -uid");
  });
});
