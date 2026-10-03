import { describe, it, expect } from 'vitest'
import { moveProductPosition, productsInGroup, revertSortableMove } from '../warehousePositions'

const pos = (product_id: string, group_id: string | null, sort_order: number) => ({ product_id, group_id, sort_order })

function names(list: { product_id: string }[]) {
  return list.map(p => p.product_id)
}

describe('moveProductPosition', () => {
  it('reorders within a group by the drop index', () => {
    const positions = [pos('a', 'g1', 1), pos('b', 'g1', 2), pos('c', 'g1', 3), pos('d', 'g1', 4)]
    moveProductPosition(positions, 'd', 'g1', 0)
    expect(names(productsInGroup(positions, 'g1'))).toEqual(['d', 'a', 'b', 'c'])
    moveProductPosition(positions, 'd', 'g1', 2)
    expect(names(productsInGroup(positions, 'g1'))).toEqual(['a', 'b', 'd', 'c'])
  })

  it('moves into another group at the drop index (regression: landed at its old array position)', () => {
    const positions = [
      pos('lemon', 'g1', 5), pos('cola', 'g1', 1),
      pos('takis', 'g2', 1), pos('chips', 'g2', 2), pos('nuts', 'g2', 3),
    ]
    moveProductPosition(positions, 'lemon', 'g2', 0)
    expect(names(productsInGroup(positions, 'g2'))).toEqual(['lemon', 'takis', 'chips', 'nuts'])
    expect(names(productsInGroup(positions, 'g1'))).toEqual(['cola'])
    expect(positions.find(p => p.product_id === 'lemon')!.group_id).toBe('g2')
  })

  it('renumbers both groups contiguously from 1', () => {
    const positions = [pos('a', 'g1', 3), pos('b', 'g1', 7), pos('c', 'g2', 2), pos('d', 'g2', 9)]
    moveProductPosition(positions, 'a', 'g2', 1)
    expect(productsInGroup(positions, 'g2').map(p => p.sort_order)).toEqual([1, 2, 3])
    expect(productsInGroup(positions, 'g1').map(p => p.sort_order)).toEqual([1])
  })

  it('supports the ungrouped list (null) and clamps the index', () => {
    const positions = [pos('a', null, 1), pos('b', 'g1', 1)]
    moveProductPosition(positions, 'b', null, 99)
    expect(names(productsInGroup(positions, null))).toEqual(['a', 'b'])
  })

  it('ignores unpositioned products (negative sort_order)', () => {
    const positions = [pos('a', 'g1', 1), pos('x', null, -1), pos('b', 'g1', 2)]
    moveProductPosition(positions, 'b', null, 0)
    expect(names(productsInGroup(positions, null))).toEqual(['b'])
    expect(positions.find(p => p.product_id === 'x')!.sort_order).toBe(-1)
  })
})

describe('productsInGroup', () => {
  it('returns a group sorted by sort_order regardless of array order', () => {
    const positions = [pos('c', 'g1', 3), pos('a', 'g1', 1), pos('b', 'g1', 2), pos('z', 'g2', 1)]
    expect(names(productsInGroup(positions, 'g1'))).toEqual(['a', 'b', 'c'])
  })
})

describe('revertSortableMove', () => {
  function list(ids: string[]) {
    const el = document.createElement('div')
    el.append(document.createTextNode(''))
    for (const id of ids) {
      const row = document.createElement('div')
      row.id = id
      el.append(row)
    }
    el.append(document.createTextNode('')) // Vue fragment end anchor
    return el
  }
  const ids = (el: HTMLElement) => [...el.children].map(c => c.id)

  it('puts a row moved within the same list back at its old index', () => {
    const el = list(['a', 'b', 'c', 'd'])
    const d = el.querySelector('#d') as HTMLElement
    el.insertBefore(d, el.querySelector('#a')) // what SortableJS did
    revertSortableMove({ item: d, from: el, oldIndex: 3 })
    expect(ids(el)).toEqual(['a', 'b', 'c', 'd'])
    expect(el.lastChild!.nodeType).toBe(Node.TEXT_NODE) // still before the end anchor
  })

  it('puts a row moved into another list back into its source list', () => {
    const from = list(['a', 'b'])
    const to = list(['x', 'y'])
    const a = from.querySelector('#a') as HTMLElement
    to.insertBefore(a, to.querySelector('#y'))
    revertSortableMove({ item: a, from, oldIndex: 0 })
    expect(ids(from)).toEqual(['a', 'b'])
    expect(ids(to)).toEqual(['x', 'y'])
  })
})
