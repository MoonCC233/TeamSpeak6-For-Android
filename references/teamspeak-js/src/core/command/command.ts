const ESCAPE_MAP: Array<[string, string]> = [
  ["\\", "\\\\"],
  ["/", "\\/"],
  [" ", "\\s"],
  ["|", "\\p"],
  ["\x07", "\\a"],
  ["\x08", "\\b"],
  ["\x0C", "\\f"],
  ["\n", "\\n"],
  ["\r", "\\r"],
  ["\t", "\\t"],
  ["\x0B", "\\v"],
];

/**
 * Escape a value for the command line: the characters that delimit a command
 * (space, `|`, `/`, the backslash itself, control characters) become their
 * two-character escapes.
 */
export function escape(s: string): string {
  let result = s;
  for (const [from, to] of ESCAPE_MAP) {
    result = result.split(from).join(to);
  }
  return result;
}

/** Undo {@link escape}, in one pass so that `\\s` comes back as `\s`, not a space. */
export function unescape(s: string): string {
  // Process escape sequences in a single pass to avoid double-substitution
  let result = "";
  let i = 0;
  while (i < s.length) {
    if (s[i] === "\\" && i + 1 < s.length) {
      const next = s[i + 1];
      switch (next) {
        case "\\":
          result += "\\";
          i += 2;
          break;
        case "/":
          result += "/";
          i += 2;
          break;
        case "s":
          result += " ";
          i += 2;
          break;
        case "p":
          result += "|";
          i += 2;
          break;
        case "a":
          result += "\x07";
          i += 2;
          break;
        case "b":
          result += "\x08";
          i += 2;
          break;
        case "f":
          result += "\x0C";
          i += 2;
          break;
        case "n":
          result += "\n";
          i += 2;
          break;
        case "r":
          result += "\r";
          i += 2;
          break;
        case "t":
          result += "\t";
          i += 2;
          break;
        case "v":
          result += "\x0B";
          i += 2;
          break;
        case undefined:
        default:
          result += s[i] ?? "";
          i++;
          break;
      }
    } else {
      result += s[i] ?? "";
      i++;
    }
  }
  return result;
}

/** A command line, parsed. */
export interface Command {
  /** The command's name, or `""` for a data row, which carries no name. */
  readonly name: string;
  /** Its parameters, unescaped. A flag such as `-uid` maps to `""`. */
  readonly params: Readonly<Record<string, string>>;
}

/** Build a command line from a parameter map; keys and values are escaped alike. */
export function buildCommand(cmd: string, params: Readonly<Record<string, string>>): string {
  const parts: string[] = [escape(cmd)];
  for (const [k, v] of Object.entries(params)) {
    parts.push(`${escape(k)}=${escape(v)}`);
  }
  return parts.join(" ");
}

/** Build a TS3 command string preserving parameter order. */
export function buildCommandOrdered(
  cmd: string,
  params: ReadonlyArray<readonly [string, string]>,
): string {
  const parts: string[] = [escape(cmd)];
  for (const [k, v] of params) {
    // A flag such as `-uid` is a bare switch. `-uid=1` happens to be accepted
    // over the voice protocol, but it is not the documented form and nothing
    // promises another transport tolerates it.
    parts.push(k.startsWith("-") && v === "" ? escape(k) : `${escape(k)}=${escape(v)}`);
  }
  return parts.join(" ");
}
