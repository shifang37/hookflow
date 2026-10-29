/**
 * Tailwind is here for layout utilities only.
 *
 * `preflight: false` is deliberate: Ant Design v5 ships its own reset and a
 * Tailwind preflight on top of it resets AntD's form/list styles into a state
 * that is hard to debug (buttons lose padding, tables lose borders). Utilities
 * like `flex`/`gap-4`/`p-6` compose fine with AntD; a global reset does not.
 */
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  corePlugins: {
    preflight: false,
  },
  theme: {
    extend: {
      colors: {
        // Kept in sync with the chart palette in src/theme.ts.
        success: '#52c41a',
        warning: '#faad14',
        danger: '#ff4d4f',
      },
    },
  },
  plugins: [],
}
