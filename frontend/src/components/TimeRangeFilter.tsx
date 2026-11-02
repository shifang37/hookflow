import { DatePicker } from 'antd'
// `Dayjs` is the date type AntD's pickers speak (antd depends on dayjs, so the package is
// already in node_modules). The import is type-only: it adds no runtime dependency of our
// own, the instances come out of the picker and go straight back into it.
import type { Dayjs } from 'dayjs'
import { toApiTimestamp } from '@/api/time'

/**
 * A picked window, or nothing — never half of one. The picker only commits once both
 * ends are chosen (`allowEmpty` stays off), so a page never has to decide what an
 * open-ended range means: the backend accepts one-sided windows, but nobody asked for
 * them in the console, and "since" without "until" on a 2s poll is just "everything".
 */
export type TimeRange = [Dayjs, Dayjs] | null

/**
 * The `from` / `to` query parameters for a range, in the API's naive-UTC format.
 *
 * Both keys are always present (as `undefined` when there is no range) so a page can
 * spread the result into its query object; axios drops undefined params, which the
 * request-level tests assert.
 */
export function timeRangeQuery(range: TimeRange): { from: string | undefined; to: string | undefined } {
  return range === null
    ? { from: undefined, to: undefined }
    : { from: toApiTimestamp(range[0].toDate()), to: toApiTimestamp(range[1].toDate()) }
}

interface Props {
  value: TimeRange
  onChange: (next: TimeRange) => void
}

/**
 * The time-window filter shared by the event stream and the deliveries page.
 *
 * The picker works in the viewer's local time, like every timestamp column on those
 * pages; the conversion to the API's UTC happens in {@link timeRangeQuery}, in one
 * place. `to` is exclusive on the backend, which is what an operator means by
 * "until 10:00".
 */
export function TimeRangeFilter({ value, onChange }: Props) {
  return (
    <DatePicker.RangePicker
      showTime
      allowClear
      value={value}
      placeholder={['开始时间', '结束时间']}
      // Two date-time inputs do not fit a 390px viewport side by side with the other
      // filters; let the picker take the row there and size itself on wider screens.
      className="w-full sm:w-auto"
      onChange={(next) => {
        // With `allowEmpty` off the picker hands back both ends or null, but its type
        // still allows a null end. Anything incomplete means "no filter".
        onChange(next !== null && next[0] !== null && next[1] !== null ? [next[0], next[1]] : null)
      }}
    />
  )
}
