import { describe, expect, it } from "vitest";
import fc from "fast-check";
import { responseSpec } from "../core/internal.js";
import { CommandTracker } from "./commands.js";
import type { CommandResult } from "./commands.js";

// The tracker pairs what the server sends with the commands in flight. A server
// chooses what arrives and when — rows, notifications, errors for return codes
// nobody sent — so any order of them must leave every command answered once at
// most, with the rows its own answer holds, and must never throw.

const OWN_CLIENT = 7;

/** Commands with each answer shape that arrives before the error, and two with none. */
const PLAIN = ["whoami", "clientlist", "banlist", "servergrouplist", "channelcreate"] as const;
/** The commands answered by a notification after the error, matched on an echoed field. */
const CORRELATED = ["ftinitupload", "ftinitdownload"] as const;

/** The return code `which` names among those issued, or one nobody was given. */
const pick = (issued: number[], which: number): number =>
  issued.length === 0 ? 999 : issued[which % issued.length]!;

/** What a data row may be tagged with: nothing, a notification some command declares, or one none does. */
const SOURCES = [
  "",
  "notifybanlist",
  "notifyservergrouplist",
  "notifychannelcreated",
  "notifyclientmoved",
] as const;

const NOTIFICATIONS = [
  "notifybanlist",
  "notifyservergrouplist",
  "notifychannelcreated",
  "notifystartupload",
  "notifystartdownload",
  "notifystatusfiletransfer",
  "notifyclientmoved",
  "notifyunknown",
] as const;

type Op =
  | { op: "register"; command: string; correlation: string | undefined }
  | { op: "buffer"; source: string; invoker: string }
  | { op: "offer"; name: string; echo: string; status: string }
  | { op: "resolve"; which: number; fails: boolean }
  | { op: "unregister"; which: number }
  | { op: "discard" }
  | { op: "welcome" }
  | { op: "reset" };

const sequences = (commands: readonly string[]): fc.Arbitrary<Op[]> =>
  fc.array(
    fc.oneof(
      {
        weight: 4,
        arbitrary: fc.record({
          op: fc.constant("register" as const),
          command: fc.constantFrom(...commands),
          correlation: fc.constantFrom("1", "2", undefined),
        }),
      },
      {
        weight: 6,
        arbitrary: fc.record({
          op: fc.constant("buffer" as const),
          source: fc.constantFrom(...SOURCES),
          invoker: fc.constantFrom("7", "8", ""),
        }),
      },
      {
        weight: 3,
        arbitrary: fc.record({
          op: fc.constant("offer" as const),
          name: fc.constantFrom(...NOTIFICATIONS),
          echo: fc.constantFrom("1", "2", "3"),
          status: fc.constantFrom("0", "2568", "x", ""),
        }),
      },
      {
        weight: 6,
        arbitrary: fc.record({
          op: fc.constant("resolve" as const),
          which: fc.integer({ min: 0, max: 8 }),
          fails: fc.boolean(),
        }),
      },
      {
        weight: 1,
        arbitrary: fc.record({
          op: fc.constant("unregister" as const),
          which: fc.integer({ min: 0, max: 8 }),
        }),
      },
      { weight: 1, arbitrary: fc.record({ op: fc.constant("discard" as const) }) },
      { weight: 3, arbitrary: fc.record({ op: fc.constant("welcome" as const) }) },
      { weight: 1, arbitrary: fc.record({ op: fc.constant("reset" as const) }) },
    ),
    { maxLength: 40, size: "max" },
  );

type Row = { source: string; params: Record<string, string> };

/** Runs a sequence on a tracker, and on a model of what it must do, when it can have one. */
async function run(sequence: Op[], modelled: boolean): Promise<void> {
  const tracker = new CommandTracker(() => OWN_CLIENT);
  const outcomes = new Map<number, CommandResult[]>();
  let registrations = 0;
  /** The return codes in flight, as the tracker numbers them, and their registration. */
  const inFlight = new Map<number, { id: number; command: string }>();
  const expected = new Map<number, CommandResult>();
  const issued: number[] = [];
  let held: Row[] = [];
  let welcomed = false;

  for (const step of sequence) {
    switch (step.op) {
      case "register": {
        const id = registrations++;
        const [rc, promise] = tracker.register(responseSpec(step.command), step.correlation);
        void promise.then((result) => outcomes.set(id, [...(outcomes.get(id) ?? []), result]));
        inFlight.set(rc, { id, command: step.command });
        issued.push(rc);
        break;
      }
      case "buffer": {
        const params = { invokerid: step.invoker, n: String(held.length) };
        tracker.buffer(params, step.source);
        if (welcomed && inFlight.size > 0) held.push({ source: step.source, params });
        break;
      }
      case "offer":
        tracker.offerCorrelated(step.name, { clientftfid: step.echo, status: step.status });
        break;
      case "resolve": {
        const rc = pick(issued, step.which);
        const err = step.fails ? new Error("refused") : null;
        const rows = held;
        held = [];
        tracker.resolve(rc, err);
        const entry = inFlight.get(rc);
        if (modelled && entry) {
          const spec = responseSpec(entry.command);
          expected.set(entry.id, {
            err,
            data: rows
              .filter((row) => {
                if (row.source === "") return true;
                if (!spec?.notifications?.includes(row.source)) return false;
                return !spec.ownRowsOnly || row.params["invokerid"] === String(OWN_CLIENT);
              })
              .map((row) => row.params),
          });
          inFlight.delete(rc);
        }
        break;
      }
      case "unregister": {
        const rc = pick(issued, step.which);
        tracker.unregister(rc);
        inFlight.delete(rc);
        break;
      }
      case "discard":
        tracker.discardBuffer();
        held = [];
        break;
      case "welcome":
        tracker.signalWelcomeComplete();
        welcomed = true;
        held = [];
        break;
      case "reset": {
        const err = new Error("reset");
        tracker.reset(err);
        for (const { id } of inFlight.values()) expected.set(id, { err, data: [] });
        inFlight.clear();
        issued.length = 0;
        held = [];
        welcomed = false;
        break;
      }
    }
  }
  await new Promise((resolve) => setImmediate(resolve));

  for (const [id, results] of outcomes) expect(results, `command ${id}`).toHaveLength(1);
  if (!modelled) return;
  // Each command was answered as the model says, and no other was.
  for (const [id, want] of expected) expect(outcomes.get(id)?.[0], `command ${id}`).toEqual(want);
  for (const id of outcomes.keys()) expect(expected.has(id), `command ${id} answered`).toBe(true);
}

describe("the command tracker", () => {
  it("answers each command with the rows its own answer holds, in any order of events", async () => {
    await fc.assert(
      fc.asyncProperty(sequences(PLAIN), (sequence) => run(sequence, true)),
      { numRuns: 500 },
    );
  });

  it("takes answers after the error without throwing or answering twice", async () => {
    await fc.assert(
      fc.asyncProperty(sequences([...PLAIN, ...CORRELATED]), (sequence) => run(sequence, false)),
      { numRuns: 500 },
    );
  });
});
