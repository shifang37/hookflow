/**
 * Cap a drawer at the viewport width.
 *
 * Ant Design applies `width` as an inline size and does not clamp it to the viewport. A
 * fixed 900px drawer on a 390px phone therefore makes the whole document horizontally
 * scrollable, which is the one mobile failure this console must not have. CSS `min()` is
 * supported by every browser the console targets (and by jsdom as an opaque value, so the
 * tests can still assert the configuration).
 */
export function drawerWidth(maxPx: number): string {
  return `min(${maxPx}px, 100vw)`
}
