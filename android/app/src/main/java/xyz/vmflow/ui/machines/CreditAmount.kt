package xyz.vmflow.ui.machines

/**
 * Parses a user-entered credit amount for the Send Credit sheet. Mirrors iOS
 * `SendCreditSheet.amount` (`MachineDetailView.swift` ~L803-807): accepts
 * `,` as a decimal separator by normalizing it to `.` before parsing, and
 * requires a strictly positive value — zero, negative, and unparseable input
 * all yield `null`.
 */
internal fun parseCreditAmount(text: String): Double? {
    val normalized = text.trim().replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    return value.takeIf { it > 0.0 }
}
