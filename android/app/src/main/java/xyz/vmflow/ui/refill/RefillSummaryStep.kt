package xyz.vmflow.ui.refill

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.models.TourLogEntry
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.VMflowBlue
import xyz.vmflow.ui.theme.VMflowBlueDark

/**
 * Tour summary: four headline figures, then one row per machine showing its
 * contribution — skipped machines marked as such rather than read as a
 * zero-fill visit. Everything here is **derived from [tourLog]**, never from
 * separately counted state: the log is what survives an app kill
 * ([RefillViewModel.resumeTour] restores it verbatim), so counters kept
 * beside it could disagree with the log they summarize.
 *
 * State and callbacks in, no ViewModel reference — same contract as
 * [PackingStep] and [RefillStepContent]. The four figures below mirror
 * [RefillUiState.machinesVisited]/[traysRefilled]/[totalItemsAdded]/
 * [machinesSkipped], recomputed locally from the same [tourLog] rather than
 * received as separate parameters, so there is exactly one source of truth
 * for "what happened this tour" in this file.
 *
 * Ported from iOS `RefillSummaryView.swift`: the success animation and the
 * four stat cards (`statCard`) match its wording and colour intent as
 * closely as Android's fixed-token palette allows. The per-machine list
 * below them has **no iOS equivalent** — iOS shows only the four aggregates
 * — added here per this task's brief.
 *
 * @param onDone Fires when the driver taps "Done". The caller — not this
 *   composable — is responsible for calling `RefillViewModel.reset()`
 *   **before** invoking this callback: `reset()` deliberately leaves
 *   `isLoading = true` with the entry gate re-armed, which is only correct
 *   once this screen has actually been left and would otherwise show it a
 *   permanent spinner on itself.
 */
@Composable
fun RefillSummaryStep(
    tourLog: List<TourLogEntry>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val machinesVisited = tourLog.count { !it.skipped }
    val traysRefilled = tourLog.sumOf { it.traysRefilled }
    val totalItemsAdded = tourLog.sumOf { it.totalAdded }
    val machinesSkipped = tourLog.count { it.skipped }
    val totalMachines = tourLog.size

    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(targetValue = 1f, animationSpec = tween(600))
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                SummaryHeader(
                    machinesVisited = machinesVisited,
                    machinesSkipped = machinesSkipped,
                    totalMachines = totalMachines,
                    iconScale = scale.value
                )
            }

            item(key = "stats") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatRow(
                        icon = Icons.Default.Storefront,
                        label = stringResource(R.string.refill_summary_stat_machines_visited),
                        value = "$machinesVisited",
                        color = VMflowBlue
                    )
                    SummaryStatRow(
                        icon = Icons.Default.Inventory2,
                        label = stringResource(R.string.refill_summary_stat_trays_refilled),
                        value = "$traysRefilled",
                        color = StockGreen
                    )
                    SummaryStatRow(
                        icon = Icons.Default.LocalShipping,
                        label = stringResource(R.string.refill_summary_stat_items_added),
                        value = "$totalItemsAdded",
                        color = VMflowBlueDark
                    )
                    // Matches iOS: the skipped stat only appears when there
                    // is anything to report — a fleet with a perfect tour
                    // never shows a "0 skipped" row.
                    if (machinesSkipped > 0) {
                        SummaryStatRow(
                            icon = Icons.Default.SkipNext,
                            label = stringResource(R.string.refill_summary_stat_machines_skipped),
                            value = "$machinesSkipped",
                            color = StockOrange
                        )
                    }
                }
            }

            if (tourLog.isNotEmpty()) {
                item(key = "machines-header") {
                    Text(
                        text = stringResource(R.string.refill_summary_machines_header),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // `tourLog` entries carry no derived/computed fields beyond
                // what `TourLogEntry` already stores, so — unlike
                // `RefillStep.kt`'s `trayRows`/`machinePickerRows` — there is
                // nothing to hoist into a separate row-state list; the log
                // itself is the per-row state.
                items(items = tourLog, key = { it.machineId }) { entry ->
                    SummaryMachineRow(entry)
                }
            }
        }

        HorizontalDivider()

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .defaultMinSize(minHeight = 56.dp)
        ) {
            Text(stringResource(R.string.action_done), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SummaryHeader(
    machinesVisited: Int,
    machinesSkipped: Int,
    totalMachines: Int,
    iconScale: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CheckCircle,
            // Decorative: the headline right below states the same thing in
            // words, so a screen reader would otherwise announce it twice.
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .scale(iconScale),
            tint = StockGreen
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.refill_summary_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (machinesSkipped > 0) {
                pluralStringResource(
                    R.plurals.refill_summary_subtitle_partial,
                    totalMachines,
                    machinesVisited,
                    totalMachines
                )
            } else {
                stringResource(R.string.refill_summary_subtitle_all_done)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SummaryStatRow(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    // Decorative: the label text beside it names the stat.
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * One row per [TourLogEntry]: the machine's name plus its contribution, or
 * — for a skipped stop — a status label instead of a "0 trays · 0 items"
 * reading that would otherwise be indistinguishable from a visited machine
 * that genuinely needed nothing.
 */
@Composable
private fun SummaryMachineRow(entry: TourLogEntry, modifier: Modifier = Modifier) {
    val statusColor = if (entry.skipped) StockOrange else StockGreen

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.skipped) Icons.Default.SkipNext else Icons.Default.CheckCircle,
                // Decorative: the line below spells out skipped vs.
                // refilled in words.
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.machineName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (entry.skipped) {
                        stringResource(R.string.refill_summary_row_skipped)
                    } else {
                        stringResource(
                            R.string.refill_summary_row_contribution,
                            pluralStringResource(
                                R.plurals.refill_summary_row_trays,
                                entry.traysRefilled,
                                entry.traysRefilled
                            ),
                            pluralStringResource(
                                R.plurals.refill_pack_items,
                                entry.totalAdded,
                                entry.totalAdded
                            )
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.skipped) {
                        statusColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
