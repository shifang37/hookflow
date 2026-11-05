import { act } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SESSION_EXPIRED_EVENT } from '@/api/client'
import { ANONYMOUS, sessionQueryKey } from '@/api/session'
import { adminSession, renderWithProviders } from '@/test/render'
import { SessionExpiryListener } from './SessionExpiryListener'

describe('SessionExpiryListener', () => {
  it('replaces the cached identity when an admin request reports a 401', () => {
    const { queryClient } = renderWithProviders(<SessionExpiryListener />)
    queryClient.setQueryData(sessionQueryKey, adminSession)

    act(() => {
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
    })

    expect(queryClient.getQueryData(sessionQueryKey)).toEqual(ANONYMOUS)
  })
})
