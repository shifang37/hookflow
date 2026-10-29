import { describe, expect, it } from 'vitest'
import type { BatchReplay } from '@/api/types'
import { describeBatchReplay } from './replayMessage'

const replayed = (deliveryId: number) => ({
  deliveryId,
  replayed: true,
  status: 'PENDING',
  code: null,
  message: null,
})

describe('describeBatchReplay', () => {
  it('reports a clean batch as a positive count, not as "done"', () => {
    const result: BatchReplay = {
      replayed: 3,
      rejected: 0,
      results: [replayed(1), replayed(2), replayed(3)],
    }

    expect(describeBatchReplay(result)).toBe('已重放 3 条投递')
  })

  it('names the first rejection, because that is the id the operator has to look at', () => {
    const result: BatchReplay = {
      replayed: 1,
      rejected: 1,
      results: [
        replayed(1),
        {
          deliveryId: 2,
          replayed: false,
          status: null,
          code: 40903,
          message: 'Only DEAD deliveries can be replayed (current: SUCCEEDED)',
        },
      ],
    }

    expect(describeBatchReplay(result)).toBe(
      '已重放 1 条，1 条被拒绝：Only DEAD deliveries can be replayed (current: SUCCEEDED)',
    )
  })
})
