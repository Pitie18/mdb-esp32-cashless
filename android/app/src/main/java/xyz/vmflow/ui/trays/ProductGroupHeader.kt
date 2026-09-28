package xyz.vmflow.ui.trays

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.ProductStockGroup
import xyz.vmflow.data.TrayStockState
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import xyz.vmflow.ui.theme.VMflowBlue
import xyz.vmflow.ui.theme.VMflowBlueLight

/**
 * A product group's status as the tray list and stock map show it — the same
 * four buckets as the PWA (`ProductGroupHeader.vue` / `TrayStockGrid.vue`):
 * sold out (red), low (amber), top off (blue), OK (green). A group that does
 * not need refill is OK even if its state says otherwise (a FILL group that is
 * already full).
 */
internal enum class GroupStatus { CRITICAL, LOW, FILL, OK }

internal fun groupStatus(needsRefill: Boolean, state: TrayStockState): GroupStatus = when {
    !needsRefill -> GroupStatus.OK
    state == TrayStockState.CRITICAL -> GroupStatus.CRITICAL
    state == TrayStockState.LOW -> GroupStatus.LOW
    else -> GroupStatus.FILL
}

/** Fixed tokens, not scheme roles — same reasoning as the analysis/refill grids. */
internal fun GroupStatus.color(isDark: Boolean): Color = when (this) {
    GroupStatus.CRITICAL -> StockRed
    GroupStatus.LOW -> StockOrange
    GroupStatus.FILL -> if (isDark) VMflowBlueLight else VMflowBlue
    GroupStatus.OK -> StockGreen
}

@Composable
internal fun GroupStatus.label(): String = stringResource(
    when (this) {
        GroupStatus.CRITICAL -> R.string.tray_group_state_critical
        GroupStatus.LOW -> R.string.tray_group_state_low
        GroupStatus.FILL -> R.string.tray_group_state_fill
        GroupStatus.OK -> R.string.tray_group_state_ok
    }
)

/**
 * Summary card for a product that sits in two or more slots: summed stock,
 * what is missing, status pill, and a segmented bar with one segment per slot.
 * The slots follow as indented [TrayRow]s below it.
 */
@Composable
fun ProductGroupHeader(
    group: ProductStockGroup,
    needsRefill: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val status = groupStatus(needsRefill, group.state)
    val statusColor = status.color(isDark)
    // Bar colour: sold out and low share amber, like the PWA's header bar.
    val barColor = when (status) {
        GroupStatus.OK -> StockGreen
        GroupStatus.FILL -> statusColor
        else -> StockOrange
    }
    val product = group.trays.firstNotNullOfOrNull { it.products }
    val slots = group.trays.sortedBy { it.itemNumber }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(
                    imagePath = product?.imagePath,
                    contentDescription = product?.name,
                    size = 32.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = product?.name ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = pluralStringResource(R.plurals.tray_group_slots, slots.size, slots.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "${group.currentStock} / ${group.capacity}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (group.deficit > 0) {
                            Text(
                                text = pluralStringResource(R.plurals.tray_group_missing, group.deficit, group.deficit),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (group.emptySlots > 0 && !needsRefill) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.tray_group_empty_slots,
                                    group.emptySlots,
                                    group.emptySlots,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            val barDescription = "${group.currentStock} / ${group.capacity}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .semantics { contentDescription = barDescription },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                slots.forEach { tray ->
                    val fraction = if (tray.capacity > 0) {
                        (tray.currentStock.toFloat() / tray.capacity).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(barColor),
                            )
                        }
                    }
                }
            }
        }
    }
}
