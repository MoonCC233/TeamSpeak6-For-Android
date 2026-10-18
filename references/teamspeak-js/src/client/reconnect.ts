import { ErrorCode, Reason, ServerError, AlreadyConnectedError } from "@honeybbq/teamspeak";
import { InvalidAddressError, RemovedError } from "./errors.js";

/**
 * Server errors where retrying cannot help.
 *
 * Deliberately a short list of codes known to be refusals, rather than "any
 * ServerError". The server also answers transiently: after a dropped
 * connection it still holds the old session, and a reconnect with the same
 * identity is refused with 521 "too many clones already connected" until it
 * reaps the zombie — a condition that clears itself and is worth retrying.
 *
 * The asymmetry is deliberate. Retrying a permanent failure costs a few bounded
 * attempts; not retrying a transient one means never recovering.
 */
const PERMANENT_SERVER_ERRORS: ReadonlySet<number> = new Set([
  ErrorCode.ConnectFailedBanned,
  ErrorCode.BanFlooding,
  ErrorCode.ServerInvalidPassword,
  ErrorCode.ChannelInvalidPassword,
  ErrorCode.PermissionsClientInsufficient,
]);

/**
 * Whether a failure is worth another attempt.
 *
 * Transport failures are retried; so is a server that stopped, and a server
 * error other than the known refusals — a ban, flooding, a wrong server or
 * channel password, missing permissions — since some, such as 521 "too many
 * clones" right after a drop, clear by themselves. A kick or a ban is not:
 * someone removed the client on purpose. Nor is an AlreadyConnectedError: the
 * client was connected by other means; nor an InvalidAddressError.
 */
export function defaultShouldRetry(error: Error | undefined): boolean {
  // The client is connected, or connecting, already: there is nothing to retry.
  if (error instanceof AlreadyConnectedError) return false;
  // An address that cannot be one stays that way.
  if (error instanceof InvalidAddressError) return false;
  if (error instanceof RemovedError) {
    return error.reason !== Reason.KickServer && error.reason !== Reason.KickServerBan;
  }
  return !(error instanceof ServerError && PERMANENT_SERVER_ERRORS.has(error.code));
}
