/**
 * The host key policy as a compile-time rule, checked by `pnpm typecheck`.
 *
 * Nothing here runs: vitest collects `src/**\/*.test.ts`, and this file is not
 * one of them. It is a file TypeScript has to accept, so what it proves is
 * exactly what does and does not compile. Every deliberately wrong case carries
 * `@ts-expect-error`, which fails the build as soon as that case starts
 * compiling — a rule that quietly stopped being enforced would otherwise look
 * like a passing test.
 *
 * Each case is one short line, inside a call. TypeScript reports a rejected
 * object literal on the line that declares it, and a line the formatter wraps
 * moves its members onto others, away from the directive; every line here is
 * kept short enough to stay as it is written.
 *
 * It imports from the entry point, because that is what a user has.
 */
import { QueryClient } from "./index.js";
import type { QueryClientOptions, SshDialOptions, SshOptions } from "./index.js";
import type { SshClient } from "./index.js";

declare const client: SshClient;
declare function takesOptions(options: SshOptions): void;
declare function takesDial(options: SshDialOptions): void;
declare function takesClient(options: QueryClientOptions): void;

/** A host and a login, which each arm adds its policy to. */
const login = { host: "h", username: "u", password: "p" } as const;
/** A connection the caller made, with nothing else. */
const supplied = { host: "h", connection: client } as const;
/** A check to reuse, so the lines below stay short. */
const check = (): void => {};
/** `false` rather than `true`, which the type does not take. */
const notTrue = { insecureIgnoreHostKey: false };

// ---- The three arms, each of which compiles --------------------------------

/** Your own check, which throws to refuse a key. */
const withCallback: SshOptions = { ...login, hostKey: check };
/** A known_hosts file to check the server against. */
const withKnownHosts: SshOptions = { ...login, knownHosts: "/etc/ssh/ssh_known_hosts" };
/** Accept anything; for a test or a throwaway server. */
const withInsecure: SshOptions = { ...login, insecureIgnoreHostKey: true };
/** A connection the caller made: no login and no policy. */
const withConnection: SshOptions = { ...supplied };
/** A port and a signal are allowed on either shape. */
const withExtras: SshOptions = { ...login, port: 10022, knownHosts: "/k" };
const suppliedWithExtras: SshOptions = { ...supplied, port: 10022 };

takesOptions(withCallback);
takesOptions(withKnownHosts);
takesOptions(withInsecure);
takesOptions(withConnection);
takesOptions(withExtras);
takesOptions(suppliedWithExtras);
takesDial(withKnownHosts);

// ---- No policy -------------------------------------------------------------

// @ts-expect-error the host key has to be checked, and no arm was given
takesOptions({ ...login });
// @ts-expect-error and the dial options the error names take the same rule
takesDial({ ...login });
// @ts-expect-error nor can the client's ssh transport leave it out
takesClient({ transport: { kind: "ssh", ...login } });

// ---- Two policies ----------------------------------------------------------

// @ts-expect-error hostKey and knownHosts do not say which one decides
takesOptions({ ...login, hostKey: check, knownHosts: "/k" });
// @ts-expect-error nor do checking a file and accepting anything
takesOptions({ ...login, knownHosts: "/k", insecureIgnoreHostKey: true });
// @ts-expect-error nor do all three
takesOptions({ ...login, hostKey: check, knownHosts: "/k", insecureIgnoreHostKey: true });
// @ts-expect-error nor does the same pair on the client's transport options
takesClient({ transport: { kind: "ssh", ...login, hostKey: check, knownHosts: "/k" } });

// ---- A value that is not a policy ------------------------------------------

// @ts-expect-error insecureIgnoreHostKey is `true`, not a switch to set to false
takesOptions({ ...login, ...notTrue });
// @ts-expect-error and the client's transport options take the same value
takesClient({ transport: { kind: "ssh", ...login, ...notTrue } });

// ---- A supplied connection takes neither a login nor a policy --------------

// @ts-expect-error a supplied connection is authenticated already: there is no login to give
takesOptions({ ...supplied, password: "p" });
// @ts-expect-error nor a username, for the same reason
takesOptions({ ...supplied, username: "u" });
// @ts-expect-error nor a policy: its key is checked by whoever made the connection
takesOptions({ ...supplied, knownHosts: "/k" });
// @ts-expect-error nor the other policy
takesOptions({ ...supplied, insecureIgnoreHostKey: true });
// @ts-expect-error and the client's transport options take the same rule
takesClient({ transport: { kind: "ssh", ...supplied, password: "p" } });
// @ts-expect-error a policy beside a supplied connection is refused there too
takesClient({ transport: { kind: "ssh", ...supplied, knownHosts: "/k" } });

// ---- Every arm on the client's transport options ---------------------------

const transportCallback: QueryClientOptions = {
  transport: { kind: "ssh", ...login, hostKey: check },
};
const transportKnownHosts: QueryClientOptions = {
  transport: { kind: "ssh", ...login, knownHosts: "/k" },
};
const transportInsecure: QueryClientOptions = {
  transport: { kind: "ssh", ...login, insecureIgnoreHostKey: true },
};
const transportConnection: QueryClientOptions = { transport: { kind: "ssh", ...supplied } };

/**
 * What a JavaScript caller or an `as` cast hands over is checked when it runs,
 * however it got past the compiler: that is what `hostKeyDecision` is for.
 */
declare const fromJavaScript: SshOptions;

// Referenced, so the file is not types alone and every arm above is used.
export const clients: readonly QueryClient[] = [
  new QueryClient(transportCallback),
  new QueryClient(transportKnownHosts),
  new QueryClient(transportInsecure),
  new QueryClient(transportConnection),
];
export const options: readonly unknown[] = [
  withCallback,
  withKnownHosts,
  withInsecure,
  withConnection,
  withExtras,
  suppliedWithExtras,
  transportCallback,
  transportKnownHosts,
  transportInsecure,
  transportConnection,
  fromJavaScript,
];
