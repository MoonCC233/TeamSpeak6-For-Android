/**
 * Diagnostics channels: the SDK's tracing points, for an application or an
 * APM agent (OpenTelemetry instruments `diagnostics_channel` directly) to
 * subscribe to.
 *
 * A library does not decide where its traces go, so the SDK publishes them
 * here, and with no subscriber a channel costs a property read.
 *
 * Messages carry the command's name, never its parameters: those hold
 * password hashes, which the server accepts as passwords, and privilege keys.
 *
 * ```ts
 * import { tracingChannel } from "node:diagnostics_channel";
 *
 * tracingChannel("honeybbq:teamspeak:command").subscribe({
 *   start: ({ command }) => console.time(command),
 *   asyncEnd: ({ command }) => console.timeEnd(command),
 *   error: ({ command, error }) => console.error(command, error),
 * });
 * ```
 */

import {
  channel,
  tracingChannel,
  type Channel,
  type TracingChannel,
} from "node:diagnostics_channel";
import type { Client } from "./client.js";

/** What `honeybbq:teamspeak:command` reports about a command. */
export interface CommandTrace {
  /** The command's name, such as `clientlist`. */
  readonly command: string;
  /** The client sending it. Its fields are private, so it carries no secret. */
  readonly client: Client;
}

/** What `honeybbq:teamspeak:incoming` reports about a command arriving from the server. */
export interface IncomingTrace {
  /** The command's name, such as `notifytextmessage`, or `""` for a data row. */
  readonly command: string;
  /** The client receiving it. */
  readonly client: Client;
}

/**
 * Traces every command with an answer: `start` when it is sent, `asyncEnd`
 * when its answer arrives, `error` when it fails.
 */
export const commandChannel: TracingChannel<CommandTrace> = tracingChannel<CommandTrace>(
  "honeybbq:teamspeak:command",
);

/** Publishes every command the server sends. */
export const incomingChannel: Channel<IncomingTrace> = channel<IncomingTrace>(
  "honeybbq:teamspeak:incoming",
);
