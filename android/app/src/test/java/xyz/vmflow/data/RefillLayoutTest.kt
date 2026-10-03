package xyz.vmflow.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The refill layout grid's bucket rule. The interesting cases are the
 * precedence ones — a slot excluded from the tour that happens to be empty, and
 * a dialled-in slot that happens to be full — because getting either backwards
 * would colour the grid to say the opposite of what the confirm button is about
 * to book.
 */
class RefillLayoutTest {

    // ─── Precedence ──────────────────────────────────────────────────────

    @Test
    fun `not in tour wins over an empty slot`() {
        assertEquals(
            RefillSlotState.NOT_IN_TOUR,
            RefillLayout.classify(isInTour = false, fillAmount = 0, currentStock = 0, capacity = 10),
        )
    }

    @Test
    fun `not in tour wins over a dialled-in amount`() {
        // Defensive: `applyTourInclusion` zeroes excluded trays, so this
        // combination should not occur — if it ever does, the grid must still
        // say "not on this tour" rather than "being filled".
        assertEquals(
            RefillSlotState.NOT_IN_TOUR,
            RefillLayout.classify(isInTour = false, fillAmount = 5, currentStock = 0, capacity = 10),
        )
    }

    @Test
    fun `a dialled-in amount wins over a full slot`() {
        assertEquals(
            RefillSlotState.JUST_FILLED,
            RefillLayout.classify(isInTour = true, fillAmount = 1, currentStock = 10, capacity = 10),
        )
    }

    @Test
    fun `a dialled-in amount wins over an empty slot`() {
        assertEquals(
            RefillSlotState.JUST_FILLED,
            RefillLayout.classify(isInTour = true, fillAmount = 3, currentStock = 0, capacity = 10),
        )
    }

    // ─── Stock level ─────────────────────────────────────────────────────

    @Test
    fun `zero stock is empty`() {
        assertEquals(
            RefillSlotState.EMPTY,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 0, capacity = 10),
        )
    }

    @Test
    fun `one unit of ten is low`() {
        assertEquals(
            RefillSlotState.LOW,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 1, capacity = 10),
        )
    }

    @Test
    fun `just under half is low`() {
        assertEquals(
            RefillSlotState.LOW,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 4, capacity = 10),
        )
    }

    @Test
    fun `exactly half is full not low`() {
        assertEquals(
            RefillSlotState.FULL,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 5, capacity = 10),
        )
    }

    @Test
    fun `at capacity is full`() {
        assertEquals(
            RefillSlotState.FULL,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 10, capacity = 10),
        )
    }

    @Test
    fun `over capacity is full`() {
        assertEquals(
            RefillSlotState.FULL,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 12, capacity = 10),
        )
    }

    // ─── Misconfigured trays ─────────────────────────────────────────────

    @Test
    fun `zero capacity with no stock is empty`() {
        assertEquals(
            RefillSlotState.EMPTY,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 0, capacity = 0),
        )
    }

    @Test
    fun `zero capacity with stock is full and does not divide by zero`() {
        assertEquals(
            RefillSlotState.FULL,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 3, capacity = 0),
        )
    }

    @Test
    fun `negative capacity with stock is full, not low from a negative fraction`() {
        // `capacity <= 0` is the guard under test here, not the zero case above:
        // 3 / 0 is Infinity (`Infinity < LOW_FRACTION` is false, so the zero case
        // passes even without the guard). 3 / -5 = -0.6, and -0.6 < 0.5 is true —
        // without the guard this would misclassify as LOW.
        assertEquals(
            RefillSlotState.FULL,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = 3, capacity = -5),
        )
    }

    @Test
    fun `negative stock is empty`() {
        assertEquals(
            RefillSlotState.EMPTY,
            RefillLayout.classify(isInTour = true, fillAmount = 0, currentStock = -2, capacity = 10),
        )
    }
}
