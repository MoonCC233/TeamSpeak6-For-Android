import { isIP } from "node:net";
import { InvalidAddressError } from "./errors.js";

export interface HostPort {
  host: string;
  port: string;
}

/** Parse a TeamSpeak address without confusing an IPv6 colon for a port separator. */
export function splitHostPort(address: string, defaultPort = "9987"): HostPort {
  const input = address.trim();
  if (input.startsWith("[")) {
    const closingBracket = input.indexOf("]");
    if (closingBracket > 0) {
      const host = input.slice(1, closingBracket);
      const suffix = input.slice(closingBracket + 1);
      if (suffix.startsWith(":") && /^\d+$/.test(suffix.slice(1))) {
        return { host, port: suffix.slice(1) };
      }
      if (!suffix) return { host, port: defaultPort };
    }
  }

  // A raw IPv6 literal has no unambiguous port separator. Internal callers
  // normally use bracket notation, but accepting the literal with the default
  // port prevents a final IPv6 segment from being mistaken for a port.
  if (isIP(input) === 6) return { host: input, port: defaultPort };

  const lastColon = input.lastIndexOf(":");
  if (lastColon < 0) return { host: input, port: defaultPort };
  const candidatePort = input.slice(lastColon + 1);
  if (/^\d+$/.test(candidatePort) && input.indexOf(":") === lastColon) {
    return { host: input.slice(0, lastColon), port: candidatePort };
  }
  return { host: input, port: defaultPort };
}

/** Characters no host name or IP address contains, whitespace among them. */
const NOT_IN_A_HOST = /[\s\p{Cc}/?#@[\]\\]/u;

/**
 * Parse an address to connect to, and check it can be one: a host, which
 * contains nothing a host name or IP address cannot, and a port from 1 to
 * 65535. Throws an InvalidAddressError otherwise.
 */
export function parseConnectAddress(address: string): HostPort & { portNumber: number } {
  const { host, port } = splitHostPort(address);
  if (host === "") throw new InvalidAddressError(address, "no host");
  // A colon is left only in an IPv6 literal; anywhere else it is a port that
  // is not a number, which splitHostPort keeps as part of the host.
  if (NOT_IN_A_HOST.test(host) || (host.includes(":") && isIP(host) !== 6)) {
    throw new InvalidAddressError(address, "not a host name, IP address or host:port");
  }
  const portNumber = Number(port);
  if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
    throw new InvalidAddressError(address, `port ${port} is not from 1 to 65535`);
  }
  return { host, port, portNumber };
}

export function joinHostPort(host: string, port: string): string {
  const normalizedHost = stripBrackets(host);
  return isIP(normalizedHost) === 6 || normalizedHost.includes(":")
    ? `[${normalizedHost}]:${port}`
    : `${normalizedHost}:${port}`;
}

export function isIpAddress(host: string): boolean {
  return isIP(stripBrackets(host)) !== 0;
}

function stripBrackets(host: string): string {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}
