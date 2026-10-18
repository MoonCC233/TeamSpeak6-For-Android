/** The values of the table below, before freezing. */
const reasonValues = {
  /** A client joining, or moving of its own accord. */
  None: 0,
  /** A client moved by someone else. */
  Moved: 1,
  /** A client coming into or out of view because a channel was (un)subscribed. */
  Subscription: 2,
  /** A client that timed out. */
  LostConnection: 3,
  /** A client kicked from its channel. */
  KickChannel: 4,
  /** A client kicked from the server. */
  KickServer: 5,
  /** A client banned from the server. */
  KickServerBan: 6,
  /** The virtual server stopping. */
  ServerStop: 7,
  /** A client that left the server. */
  Disconnect: 8,
  /** A change to a channel the client can see. */
  ChannelUpdate: 9,
  /** A channel edited by someone. */
  ChannelEdit: 10,
  /** The server instance shutting down. */
  ServerShutdown: 11,
} as const;
/**
 * Why a client moved or left view, as the server reports it (`reasonid`).
 *
 * The names are the ones `Reason` has in teamspeak-go.
 * Events carry the reason as a plain number, since a server may send one this
 * table does not name.
 */
export const Reason: typeof reasonValues = /* @__PURE__ */ Object.freeze(reasonValues);

/** A known reason. */
export type Reason = (typeof Reason)[keyof typeof Reason];
