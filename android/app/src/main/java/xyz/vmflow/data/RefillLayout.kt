package xyz.vmflow.data

/**
 * The one bucket rule the refill step's machine layout grid needs: which of the
 * five states a slot is in, so the grid can colour it.
 *
 * Pure and unit-tested rather than inlined into `RefillStep.kt`, because it is
 * the rule that decides what a driver sees at a glance while standing in front
 * of a machine — the same reason [MachineAnalysis.scoreProduct] lives here. It
 * derives from state the refill step already has; it decides nothing about
 * stock and it is never used on a write path.
 */
enum class RefillSlotState {
    /**
     * This tour brought no stock for the slot (`RefillTray.isInTour == false`).
     * The slot is physically in the machine, so it is still drawn — dimmed, so
     * it cannot be confused with a slot that is being filled.
     */
    NOT_IN_TOUR,

    /** The driver has dialled in stock for this slot (`fillAmount > 0`). */
    JUST_FILLED,

    /** In the tour, nothing dialled in yet, and physically empty. */
    EMPTY,

    /** In the tour, nothing dialled in yet, under half full. */
    LOW,

    /** In the tour, nothing dialled in yet, at least half full. */
    FULL,
}

object RefillLayout {

    /** Below this fraction of capacity a slot reads as [RefillSlotState.LOW]. */
    const val LOW_FRACTION = 0.5

    /**
     * Classify one slot of the refill grid. Order matters and is deliberate:
     *
     * 1. Not in the tour wins over everything — the driver is not filling it on
     *    this visit, whatever its stock level, and saying otherwise would
     *    invite them to open a slot they brought nothing for.
     * 2. A dialled-in amount wins over the stock level — it is the thing the
     *    driver just did, and the thing the confirm button is about to book.
     * 3. Otherwise the physical stock level, against [LOW_FRACTION].
     *
     * A slot with `capacity <= 0` (a misconfigured tray) and no stock is
     * [RefillSlotState.EMPTY]; with stock but no capacity it is
     * [RefillSlotState.FULL] rather than dividing by zero.
     */
    fun classify(
        isInTour: Boolean,
        fillAmount: Int,
        currentStock: Int,
        capacity: Int,
    ): RefillSlotState = when {
        !isInTour -> RefillSlotState.NOT_IN_TOUR
        fillAmount > 0 -> RefillSlotState.JUST_FILLED
        currentStock <= 0 -> RefillSlotState.EMPTY
        capacity <= 0 -> RefillSlotState.FULL
        currentStock.toDouble() / capacity.toDouble() < LOW_FRACTION -> RefillSlotState.LOW
        else -> RefillSlotState.FULL
    }
}
