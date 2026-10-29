import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

/**
 * jsdom gaps that AntD and Recharts assume a browser provides.
 *
 * `matchMedia` is used by AntD's responsive grid and `ResizeObserver` by Recharts'
 * `ResponsiveContainer`; without stubs, any test that renders a layout containing
 * either throws on import-time feature detection rather than on the behaviour it was
 * meant to check.
 */
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  })
}

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
}

/**
 * jsdom implements `getComputedStyle(el)` but throws "Not implemented" for the
 * two-argument form `getComputedStyle(el, pseudoElt)`.
 *
 * rc-table measures the scrollbar width that way on every mount, so without this the
 * suite passes while printing a stack trace per table — which is exactly the noise that
 * teaches people to ignore test output. Dropping the pseudo-element argument is safe
 * here: no assertion depends on ::before/::after computed styles.
 */
const nativeGetComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = (element: Element) => nativeGetComputedStyle(element)

afterEach(() => {
  cleanup()
  window.localStorage.clear()
})
