/**
 * Ordering of products in the warehouse Positions tab.
 *
 * `sort_order` is per group (1..n within a group, `group_id` null = the
 * ungrouped list); a negative `sort_order` marks an unpositioned product.
 * The rendered lists must follow `sort_order`, never the array order of
 * `positions` — the array is not re-sorted after a move.
 */

export interface PositionLike {
  product_id: string
  group_id: string | null
  sort_order: number
}

/** Positioned products of one group, in display order. */
export function productsInGroup<T extends PositionLike>(positions: T[], groupId: string | null): T[] {
  return positions
    .filter(p => p.sort_order >= 0 && (p.group_id ?? null) === groupId)
    .sort((a, b) => a.sort_order - b.sort_order)
}

/**
 * Move a product to `newIndex` inside `toGroupId` and renumber the source and
 * target groups from 1. Mutates the position objects in place (they are the
 * reactive rows the page renders).
 */
export function moveProductPosition<T extends PositionLike>(
  positions: T[],
  productId: string,
  toGroupId: string | null,
  newIndex: number,
): void {
  const moved = positions.find(p => p.product_id === productId && p.sort_order >= 0)
  if (!moved) return
  const fromGroupId = moved.group_id ?? null

  const target = productsInGroup(positions, toGroupId).filter(p => p !== moved)
  const index = Math.max(0, Math.min(newIndex, target.length))
  target.splice(index, 0, moved)
  moved.group_id = toGroupId
  target.forEach((p, i) => { p.sort_order = i + 1 })

  if (fromGroupId !== toGroupId) {
    productsInGroup(positions, fromGroupId).forEach((p, i) => { p.sort_order = i + 1 })
  }
}

/**
 * Put a node SortableJS dragged back where it came from. SortableJS moves the
 * DOM itself; if Vue then re-renders from the updated data it patches against
 * a DOM it no longer owns and rows jump. Undo the DOM move first, change the
 * data, and let Vue do the one real move. Inserts after the previous element
 * (not before `children[oldIndex]`) so the node stays inside Vue's fragment
 * anchors when it was the last row.
 */
export function revertSortableMove(evt: { item: HTMLElement; from: HTMLElement; oldIndex?: number }): void {
  const { item, from } = evt
  const oldIndex = evt.oldIndex ?? 0
  item.remove()
  const ref = oldIndex === 0 ? from.children[0] ?? null : from.children[oldIndex - 1]?.nextSibling ?? null
  from.insertBefore(item, ref)
}
