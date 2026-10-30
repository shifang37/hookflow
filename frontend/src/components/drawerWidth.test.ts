import { describe, expect, it } from 'vitest'
import { drawerWidth } from './drawerWidth'

describe('drawerWidth', () => {
  it('caps a desktop drawer at the viewport width', () => {
    expect(drawerWidth(900)).toBe('min(900px, 100vw)')
    expect(drawerWidth(520)).toBe('min(520px, 100vw)')
  })

  it('never returns a bare pixel value that would overflow a phone', () => {
    expect(drawerWidth(760)).not.toMatch(/^\d+px$/)
  })
})
