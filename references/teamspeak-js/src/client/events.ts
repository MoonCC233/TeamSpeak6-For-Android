import type { EventMiddleware, ServerEvent } from "./types.js";

export function buildEventChain(
  middlewares: EventMiddleware[],
  base: (event: ServerEvent) => void,
): (event: ServerEvent) => void {
  let handler = base;
  for (let i = middlewares.length - 1; i >= 0; i--) {
    handler = middlewares[i]!(handler);
  }
  return handler;
}
