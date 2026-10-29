import type { ReactNode } from 'react'

/**
 * Replaces AntD v5's removed `PageHeader`: a title, one line of context, and the
 * page's own controls on the right.
 *
 * The subtitle is not decoration — it is where each page states which credential its
 * data needs, so an operator looking at an empty table knows whether the cause is
 * "nothing happened" or "you never configured a key".
 */
export function PageTitle({
  title,
  subtitle,
  actions,
}: {
  title: string
  subtitle: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="m-0 text-base font-semibold text-gray-900">{title}</h1>
        <div className="mt-1 text-xs text-gray-500">{subtitle}</div>
      </div>
      {actions !== undefined && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
