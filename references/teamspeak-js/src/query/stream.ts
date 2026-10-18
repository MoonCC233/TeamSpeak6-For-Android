import { LineReader } from "./lines.js";
import type { QueryTransport } from "./transport.js";

/**
 * The part of a duplex byte stream a line transport uses. A `node:net` socket
 * is one; so is an SSH shell channel, or a test double.
 */
export interface QueryStream {
  /** Write bytes to the peer; `callback` runs once they are flushed, with the failure if any. */
  write(data: string, callback: (error?: Error | null) => void): unknown;
  /** Close the stream. */
  end(): unknown;
  /** Listen for each chunk received. */
  on(event: "data", listener: (chunk: Uint8Array) => void): unknown;
  /** Listen for a failure, which ends the connection. */
  on(event: "error", listener: (error: Error) => void): unknown;
  /** Listen for the stream closing. */
  on(event: "close", listener: () => void): unknown;
}

/**
 * A {@link QueryTransport} over a byte stream: raw TCP, or an SSH shell.
 *
 * Both greet with a two-line banner, `TS3` and a welcome sentence, before any
 * answer. It is skipped, so the client sees only answers and notifications.
 */
export class StreamTransport implements QueryTransport {
  /** A raw TCP connection and an SSH session both carry pushed notifications. */
  readonly supportsNotifications = true;
  onLine: ((line: string) => void) | null = null;
  onClose: ((error: Error | undefined) => void) | null = null;

  #stream: QueryStream;
  #reader = new LineReader();
  #onEnd: (() => void) | undefined;
  /** How many of the two banner lines are still to come. */
  #banner = 2;
  #closing = false;
  #ended = false;
  #error: Error | undefined;

  /**
   * `onEnd` runs once the stream is closed, to release what the stream sits
   * on: an SSH session's connection, say.
   */
  constructor(stream: QueryStream, onEnd?: () => void) {
    this.#stream = stream;
    this.#onEnd = onEnd;
    stream.on("data", (chunk) => this.#handleData(chunk));
    stream.on("error", (error) => {
      this.#error = error;
    });
    stream.on("close", () => this.#handleClose());
  }

  send(line: string): Promise<void> {
    if (this.#ended) return Promise.reject(new Error("the query connection is closed"));
    return new Promise((resolve, reject) => {
      this.#stream.write(`${line}\n`, (error) => (error ? reject(error) : resolve()));
    });
  }

  close(): Promise<void> {
    if (this.#ended) return Promise.resolve();
    this.#closing = true;
    // Ending the stream is not enough to hear it close. An SSH session channel
    // is half-open, so `end()` sends EOF and stops there without reporting
    // 'close', and the hook that releases the connection underneath would never
    // run; the close is owed here as well as from the stream's own event.
    this.#stream.end();
    this.#handleClose();
    return Promise.resolve();
  }

  #handleData(chunk: Uint8Array): void {
    for (const line of this.#reader.push(chunk)) {
      if (this.#banner > 0) {
        this.#banner--;
        continue;
      }
      this.onLine?.(line);
    }
  }

  #handleClose(): void {
    if (this.#ended) return;
    this.#ended = true;
    this.#onEnd?.();
    this.onClose?.(this.#closing ? undefined : this.#error);
  }
}
