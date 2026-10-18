/**
 * Reports whether `actual` equals `expected` or equals `expected` followed by
 * only digits — the pattern TeamSpeak uses when a nickname is already taken.
 */
export function isAutoNicknameMatch(expected: string, actual: string): boolean {
  if (actual === expected) return true;
  if (!actual.startsWith(expected)) return false;
  const suffix = actual.slice(expected.length);
  return /^\d+$/.test(suffix);
}
