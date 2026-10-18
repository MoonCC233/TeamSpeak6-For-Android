/**
 * Report an error from a trace hook the way a throwing event listener's is
 * reported: rethrown on its own, as an uncaught exception.
 */
function reportHookError(error: unknown): void {
  queueMicrotask(() => {
    throw error;
  });
}

/**
 * Call a trace hook, if there is one, and never let it touch what called it:
 * a throw, or the rejection of a promise it returns, is reported as a
 * throwing listener's is. The info is built only when there is a hook.
 */
export function callHook<I>(
  hook: ((info: I) => unknown) | undefined,
  owner: object | undefined,
  info: () => I,
): void {
  if (hook === undefined) return;
  try {
    const result = hook.call(owner, info());
    if (result !== null && typeof result === "object" && "then" in result) {
      Promise.resolve(result).then(undefined, reportHookError);
    }
  } catch (error) {
    reportHookError(error);
  }
}
