package xyz.vmflow.ui.trays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.MachineAnalysis
import xyz.vmflow.data.TrayGroupInfo
import xyz.vmflow.data.TrayStockFlag
import xyz.vmflow.models.Tray
import xyz.vmflow.ui.components.MachineLayoutCell
import xyz.vmflow.ui.components.MachineLayoutGrid

/** Cell fill alpha — the same weight the analysis and refill grids use. */
private const val CELL_FILL_ALPHA = 0.35f

/**
 * Compact stock map above the machine-detail tray list — the Android
 * counterpart of the PWA's `TrayStockGrid.vue`, drawn with the shared
 * [MachineLayoutGrid] (same geometry as the analysis and refill grids).
 *
 * A cell's colour is its **product's** status, so every slot of a product
 * lights up together; an empty slot of an otherwise stocked product only gets
 * a dashed outline, an unassigned slot a muted dashed one. Tapping a cell
 * selects its product: its cells get a ring, the rest dim, and a summary with
 * a clear button appears. Tapping the selected product again (or an
 * unassigned slot) clears the selection.
 */
@Composable
fun TrayStockMap(
    trays: List<Tray>,
    index: Map<String, TrayGroupInfo>,
    selectedProductId: String?,
    onSelect: (productId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val ringColor = if (isDark) Color.White else Color.Black
    val mutedOutline = MaterialTheme.colorScheme.onSurfaceVariant
    val cellTemplate = stringResource(R.string.tray_stock_map_cell)
    val unassignedTemplate = stringResource(R.string.tray_stock_map_cell_unassigned)
    val emptyElsewhere = stringResource(R.string.tray_slot_empty_elsewhere)
    val statusLabels = GroupStatus.entries.associateWith { it.label() }

    val widths = MachineAnalysis.computeSlotWidths(trays.map { it.itemNumber })
    val cells = trays.map { tray ->
        val position = MachineAnalysis.slotRowCol(tray.itemNumber)
        val info = index[tray.id]
        val productId = tray.productId
        val base = MachineLayoutCell(
            id = tray.id,
            itemNumber = tray.itemNumber,
            row = position.row,
            column = position.column,
            width = widths[tray.itemNumber] ?: 1,
            imagePath = null,
            background = Color.Transparent,
            contentDescription = String.format(unassignedTemplate, tray.itemNumber),
            dimmed = selectedProductId != null && productId != selectedProductId,
            caption = if (productId != null) "${tray.currentStock}/${tray.capacity}" else null,
        )
        if (productId == null || info == null) {
            base.copy(dashedOutline = mutedOutline.copy(alpha = 0.35f))
        } else {
            val status = groupStatus(info.needsRefill, info.group.state)
            val slotEmpty = info.flag == TrayStockFlag.SLOT_EMPTY
            val color = status.color(isDark)
            val stateLabel = if (slotEmpty) emptyElsewhere else statusLabels.getValue(status)
            base.copy(
                background = if (slotEmpty) Color.Transparent else color.copy(alpha = if (status == GroupStatus.OK) 0.18f else CELL_FILL_ALPHA),
                dashedOutline = if (slotEmpty) mutedOutline.copy(alpha = 0.7f) else null,
                outline = ringColor.takeIf { productId == selectedProductId },
                contentDescription = String.format(
                    cellTemplate,
                    tray.itemNumber,
                    tray.products?.name ?: "—",
                    stateLabel,
                ),
            )
        }
    }
    val rowCount = cells.maxOfOrNull { it.row }?.plus(1) ?: 0

    val selectedInfo = selectedProductId?.let { id ->
        trays.firstOrNull { it.productId == id }?.let { index[it.id] }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.tray_stock_map),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (selectedInfo != null) {
                val group = selectedInfo.group
                val name = group.trays.firstNotNullOfOrNull { it.products?.name } ?: "—"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.tray_selected_product_summary,
                            name,
                            pluralStringResource(R.plurals.tray_group_slots, group.trays.size, group.trays.size),
                            group.currentStock,
                            group.capacity,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSelect(null) }) {
                        Text(stringResource(R.string.tray_clear_selection))
                    }
                }
            }
            if (rowCount > 0) {
                MachineLayoutGrid(
                    rowCount = rowCount,
                    cells = cells,
                    onCellClick = { trayId ->
                        val productId = trays.firstOrNull { it.id == trayId }?.productId
                        onSelect(if (productId != null && productId != selectedProductId) productId else null)
                    },
                )
            }
            StockMapLegend(isDark = isDark, mutedOutline = mutedOutline, emptyElsewhere = emptyElsewhere)
        }
    }
}

@Composable
private fun StockMapLegend(isDark: Boolean, mutedOutline: Color, emptyElsewhere: String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(GroupStatus.CRITICAL, GroupStatus.LOW, GroupStatus.FILL).forEach { status ->
            LegendItem(label = status.label()) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(status.color(isDark).copy(alpha = CELL_FILL_ALPHA))
                        .border(1.5.dp, status.color(isDark), RoundedCornerShape(2.dp)),
                )
            }
        }
        LegendItem(label = emptyElsewhere) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .border(1.5.dp, mutedOutline.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
            )
        }
        LegendItem(label = GroupStatus.OK.label()) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GroupStatus.OK.color(isDark).copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        swatch()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
