import { asClientFileTransferID, asServerFileTransferID } from "@honeybbq/teamspeak";
import { afterEach, describe, it, expect, vi } from "vitest";
import { createServer, type Server, type Socket } from "node:net";
import { Duplex, PassThrough, Readable, Writable } from "node:stream";
import type { FileDownloadInfo, FileUploadInfo } from "@honeybbq/teamspeak";
import { FileTransferError } from "./errors.js";
import {
  dialFileTransfer,
  downloadFileData,
  uploadFileData,
  type FileTransferTrace,
} from "./transfer.js";

/** A connected stream that records what was written to it. */
function recordingStream(fail?: Error): { stream: Duplex; written: string[] } {
  const written: string[] = [];
  const stream = new Duplex({
    read() {},
    write(chunk: Buffer, _encoding, callback) {
      if (fail) return callback(fail);
      written.push(chunk.toString());
      callback();
    },
  });
  return { stream, written };
}

/** A local TCP server; `handle` gets each connection. */
async function listen(handle: (socket: Socket) => void): Promise<Server> {
  const server = createServer(handle);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return server;
}

const port = (server: Server): number => (server.address() as { port: number }).port;

describe("dialFileTransfer", () => {
  it("uses a supplied socket factory instead of dialling", async () => {
    const { stream, written } = recordingStream();
    const calls: Array<[string, number]> = [];

    const result = await dialFileTransfer("transfer.example", 30033, "the-key", {
      socketFactory: (host, p) => {
        calls.push([host, p]);
        return stream;
      },
    });

    // Without the seam the SDK always dials its own connection, so an
    // application could not route file transfers through a proxy.
    expect(calls).toEqual([["transfer.example", 30033]]);
    expect(result).toBe(stream);
    // The ftkey handshake still runs over the supplied stream.
    expect(written).toEqual(["the-key"]);
  });

  it("surfaces a write failure as a file transfer error", async () => {
    const { stream } = recordingStream(new Error("broken pipe"));

    await expect(dialFileTransfer("h", 1, "k", { socketFactory: () => stream })).rejects.toThrow(
      /failed to send transfer key/,
    );
  });

  it("rejects with the signal's reason when aborted", async () => {
    const reason = new Error("stop");
    await expect(
      dialFileTransfer("127.0.0.1", 1, "k", { signal: AbortSignal.abort(reason) }),
    ).rejects.toBe(reason);
  });
});

describe("upload and download", () => {
  const info = {
    port: 0,
    fileTransferKey: "key",
    clientFileTransferID: asClientFileTransferID(1),
    serverFileTransferID: asServerFileTransferID(1),
  };

  it("sends the key, then the data", async () => {
    let received = "";
    let ended!: () => void;
    const done = new Promise<void>((resolve) => (ended = resolve));
    const server = await listen((socket) => {
      socket.on("data", (chunk) => (received += chunk.toString()));
      // As the server does once it has the whole file.
      socket.on("end", () => {
        socket.end();
        ended();
      });
    });
    const upload: FileUploadInfo = { ...info, port: port(server), seekPosition: 0n };

    await uploadFileData("127.0.0.1", upload, Readable.from(["hello"]));
    await done;
    server.close();

    expect(received).toBe("keyhello");
  });

  it("stops a download in progress when its signal aborts", async () => {
    const server = await listen((socket) => {
      socket.write("partial");
      // …and never finishes.
    });
    const download: FileDownloadInfo = { ...info, port: port(server), size: 100n };
    const controller = new AbortController();
    const chunks: string[] = [];
    const dest = new Writable({
      write(chunk: Buffer, _e, callback) {
        chunks.push(chunk.toString());
        controller.abort(new Error("enough"));
        callback();
      },
    });

    await expect(
      downloadFileData("127.0.0.1", download, dest, { signal: controller.signal }),
    ).rejects.toThrow();
    expect(chunks.join("")).toBe("partial");
    server.close();
  });

  it("accepts a stream that is already connected", async () => {
    const stream = new PassThrough();
    await expect(dialFileTransfer("h", 1, "k", { socketFactory: () => stream })).resolves.toBe(
      stream,
    );
  });
});

describe("file transfer limits", () => {
  const info: FileDownloadInfo = {
    clientFileTransferID: asClientFileTransferID(1),
    serverFileTransferID: asServerFileTransferID(1),
    fileTransferKey: "key",
    port: 0,
    size: 5n,
  };

  async function download(
    send: (socket: Socket) => void,
    size: bigint,
    options: { idleTimeoutMs?: number } = {},
  ): Promise<{ error: unknown; written: string }> {
    const server = await listen(send);
    const chunks: string[] = [];
    const dest = new Writable({
      write(chunk: Buffer, _e, callback) {
        chunks.push(chunk.toString());
        callback();
      },
    });
    let error: unknown;
    try {
      await downloadFileData("127.0.0.1", { ...info, port: port(server), size }, dest, options);
    } catch (e) {
      error = e;
    }
    server.close();
    return { error, written: chunks.join("") };
  }

  it("fails a download that sends more than the announced size, writing none of the excess", async () => {
    const { error, written } = await download((socket) => socket.end("hello-and-more"), 5n);
    expect(error).toBeInstanceOf(FileTransferError);
    expect((error as Error).message).toMatch(/more than the announced 5 bytes/);
    expect(written.length).toBeLessThanOrEqual(5);
    expect("hello".startsWith(written)).toBe(true);
  });

  it("fails a download that ends short", async () => {
    const { error, written } = await download((socket) => socket.end("hel"), 5n);
    expect(error).toBeInstanceOf(FileTransferError);
    expect((error as Error).message).toMatch(/ended after 3 of the announced 5 bytes/);
    expect(written).toBe("hel");
  });

  it("completes a download of exactly the announced size", async () => {
    const { error, written } = await download((socket) => socket.end("hello"), 5n);
    expect(error).toBeUndefined();
    expect(written).toBe("hello");
  });

  it("fails a transfer that goes idle, with a TimeoutError cause, and destroys the socket", async () => {
    let serverSide: Socket | undefined;
    const { error } = await download(
      (socket) => {
        serverSide = socket;
        socket.resume(); // so the peer closing is seen
        socket.write("he"); // and then nothing
      },
      5n,
      { idleTimeoutMs: 50 },
    );
    expect(error).toBeInstanceOf(FileTransferError);
    expect(((error as Error).cause as Error).name).toBe("TimeoutError");
    await vi.waitFor(() => expect(serverSide?.destroyed).toBe(true));
  });

  it("applies the idle limit to the key write", async () => {
    // A peer that never reads: the write never completes.
    const stuck = new Duplex({ read() {}, write() {} });
    const error = await dialFileTransfer("h", 1, "k", {
      socketFactory: () => stuck,
      idleTimeoutMs: 30,
    }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(FileTransferError);
    expect(((error as Error).cause as Error).name).toBe("TimeoutError");
    expect(stuck.destroyed).toBe(true);
  });

  it("lets the signal end a key write that never completes", async () => {
    const stuck = new Duplex({ read() {}, write() {} });
    const controller = new AbortController();
    const reason = new Error("enough");
    const pending = dialFileTransfer("h", 1, "k", {
      socketFactory: () => stuck,
      signal: controller.signal,
      idleTimeoutMs: 0,
    });
    controller.abort(reason);
    await expect(pending).rejects.toBe(reason);
    expect(stuck.destroyed).toBe(true);
  });
});

describe("a file transfer trace", () => {
  afterEach(() => vi.restoreAllMocks());

  const info: FileUploadInfo = {
    port: 30033,
    fileTransferKey: "SECRET-KEY-123",
    seekPosition: 0n,
    clientFileTransferID: asClientFileTransferID(1),
    serverFileTransferID: asServerFileTransferID(1),
  };

  /** A trace that records every call, in order. */
  function recording(): {
    trace: FileTransferTrace;
    calls: Array<[string, Record<string, unknown>]>;
  } {
    const calls: Array<[string, Record<string, unknown>]> = [];
    const record =
      (name: string) =>
      (i: object): void => {
        calls.push([name, i as Record<string, unknown>]);
      };
    return {
      trace: {
        dialStart: record("dialStart"),
        dialDone: record("dialDone"),
        progress: record("progress"),
        done: record("done"),
        failed: record("failed"),
      },
      calls,
    };
  }

  /** A connected stream that swallows what is written to it. */
  function sink(): Duplex {
    return new Duplex({
      read() {},
      write(_chunk, _encoding, callback) {
        callback();
      },
    });
  }

  it("reports an upload in order, with coalesced progress ending at the size", async () => {
    const { trace, calls } = recording();
    const chunk = Buffer.alloc(20 * 1024, 1);
    const chunks = Array.from({ length: 10 }, () => chunk);
    const size = chunk.length * 10;

    await uploadFileData("files.example", info, Readable.from(chunks), {
      socketFactory: () => sink(),
      trace,
    });

    const names = calls.map(([name]) => name);
    expect(names.slice(0, 2)).toEqual(["dialStart", "dialDone"]);
    expect(names.slice(-2)).toEqual(["progress", "done"]);
    expect(names.filter((n) => n === "done" || n === "failed")).toEqual(["done"]);
    expect(calls[0]![1]).toEqual({ addr: "files.example:30033" });
    expect(calls[1]![1]).toMatchObject({ addr: "files.example:30033", localAddr: "" });

    const progress = calls.filter(([name]) => name === "progress").map(([, i]) => i);
    const bytes = progress.map((p) => p["bytes"] as number);
    // 200 KiB in 20 KiB chunks: coalesced to a call per 64 KiB, plus the final one.
    expect(progress.length).toBeLessThan(10);
    expect(bytes).toEqual([...bytes].sort((a, b) => a - b));
    expect(bytes.at(-1)).toBe(size);
    for (const p of progress) expect(p["total"]).toBe(0);
    expect(calls.at(-1)![1]).toMatchObject({ bytes: size });
    expect(calls.at(-1)![1]["durationMs"]).toBeGreaterThanOrEqual(0);
  });

  it("reports a download with the size as the total", async () => {
    const { trace, calls } = recording();
    // The server sends the file once it has the key.
    const stream = new Duplex({
      read() {},
      write(_chunk, _encoding, callback) {
        callback();
        this.push("0123456789");
        this.push(null);
      },
    });
    const dest = new Writable({
      write(_chunk, _e, callback) {
        callback();
      },
    });
    const download: FileDownloadInfo = {
      port: info.port,
      fileTransferKey: info.fileTransferKey,
      clientFileTransferID: info.clientFileTransferID,
      serverFileTransferID: info.serverFileTransferID,
      size: 10n,
    };
    await downloadFileData("h", download, dest, { socketFactory: () => stream, trace });

    const last = calls.filter(([name]) => name === "progress").at(-1)![1];
    expect(last).toEqual({ bytes: 10, total: 10 });
    expect(calls.at(-1)![0]).toBe("done");
  });

  it("reports an empty transfer with a final progress of 0", async () => {
    const { trace, calls } = recording();
    await uploadFileData("h", info, Readable.from([]), { socketFactory: () => sink(), trace });
    expect(calls.filter(([n]) => n === "progress").at(-1)![1]).toEqual({ bytes: 0, total: 0 });
    expect(calls.at(-1)![0]).toBe("done");
  });

  it("reports a failed dial as failed with the stage dial, and no done", async () => {
    const { trace, calls } = recording();
    const { stream } = recordingStream(new Error("broken pipe"));
    await expect(
      uploadFileData("h", info, Readable.from(["x"]), { socketFactory: () => stream, trace }),
    ).rejects.toThrow(/failed to send transfer key/);
    expect(calls.map(([n]) => n)).toEqual(["dialStart", "failed"]);
    expect(calls[1]![1]).toMatchObject({ stage: "dial", bytes: 0 });
    expect(calls[1]![1]["error"]).toBeInstanceOf(Error);
  });

  it("reports a failure while data moves as failed with the stage transfer", async () => {
    const { trace, calls } = recording();
    const reason = new Error("stop");
    const controller = new AbortController();
    const source = new Readable({
      read() {
        this.push(Buffer.alloc(100 * 1024));
        controller.abort(reason);
      },
    });
    await expect(
      uploadFileData("h", info, source, {
        socketFactory: () => sink(),
        signal: controller.signal,
        trace,
      }),
    ).rejects.toBeDefined();
    const names = calls.map(([n]) => n);
    expect(names.filter((n) => n === "done" || n === "failed")).toEqual(["failed"]);
    expect(names).not.toContain("done");
    expect(calls.at(-1)![1]).toMatchObject({ stage: "transfer" });
  });

  it("reports dialFileTransfer on its own: dialStart then dialDone, or failed", async () => {
    const ok = recording();
    await dialFileTransfer("h", 1, "k", { socketFactory: () => sink(), trace: ok.trace });
    expect(ok.calls.map(([n]) => n)).toEqual(["dialStart", "dialDone"]);

    const bad = recording();
    const reason = new Error("stop");
    await expect(
      dialFileTransfer("h", 1, "k", { signal: AbortSignal.abort(reason), trace: bad.trace }),
    ).rejects.toBe(reason);
    expect(bad.calls.map(([n]) => n)).toEqual(["dialStart", "failed"]);
    expect(bad.calls[1]![1]).toMatchObject({ stage: "dial", error: reason });
  });

  it("never lets the transfer key appear in any info", async () => {
    const { trace, calls } = recording();
    await uploadFileData("h", info, Readable.from(["data"]), {
      socketFactory: () => sink(),
      trace,
    });
    const failing = recording();
    const { stream } = recordingStream(new Error("broken pipe"));
    await uploadFileData("h", info, Readable.from(["data"]), {
      socketFactory: () => stream,
      trace: failing.trace,
    }).catch(() => {});
    for (const [, i] of [...calls, ...failing.calls]) {
      expect(JSON.stringify(i, (_, v) => (v instanceof Error ? v.message : v))).not.toContain(
        info.fileTransferKey,
      );
    }
  });

  it("reports a throwing or rejecting hook, and the transfer still succeeds", async () => {
    const rethrown: Array<() => void> = [];
    const spy = vi.spyOn(globalThis, "queueMicrotask").mockImplementation((task) => {
      rethrown.push(task);
    });
    const boom = new Error("hook boom");
    const rejection = new Error("hook rejection");
    try {
      await uploadFileData("h", info, Readable.from([Buffer.alloc(100 * 1024)]), {
        socketFactory: () => sink(),
        trace: {
          dialStart: () => {
            throw boom;
          },
          progress: () => {
            throw boom;
          },
          // oxlint-disable-next-line typescript/no-misused-promises -- a hook that rejects is the case under test
          done: async () => {
            throw rejection;
          },
        },
      });
      await new Promise((resolve) => setImmediate(resolve));
    } finally {
      spy.mockRestore();
    }
    const errors: unknown[] = [];
    for (const task of rethrown) {
      try {
        task();
      } catch (error) {
        errors.push(error);
      }
    }
    expect(errors.filter((e) => e === boom).length).toBeGreaterThanOrEqual(2);
    expect(errors).toContain(rejection);
  });

  it("copies the trace when the call starts", async () => {
    const trace: { done?: () => void } = {};
    const pending = uploadFileData("h", info, Readable.from(["x"]), {
      socketFactory: () => sink(),
      trace,
    });
    trace.done = () => {
      throw new Error("must not run");
    };
    await expect(pending).resolves.toBeUndefined();
  });
});
