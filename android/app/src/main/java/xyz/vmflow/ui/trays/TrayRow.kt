package xyz.vmflow.ui.trays

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.data.TrayStockFlag
import xyz.vmflow.models.Tray
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.components.StockBar
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.VMflowBlue
import xyz.vmflow.ui.theme.VMflowBlueLight

/**
 * One slot of the machine-detail tray list.
 *
 * The highlight is judged on the slot's **product** ([flag], from
 * [xyz.vmflow.data.StockHealth.buildTrayGroupIndex]) — same rules as the PWA's
 * tray list: amber when the product is sold out / low and this slot has room,
 * blue for "top off" only while some product in the machine is actually low
 * ([showFillHighlight]), and a muted hint for an empty slot whose product is
 * fine thanks to another slot.
 */
@Composable
fun TrayRow(
    tray: Tray,
    onStockChange: (delta: Int) -> Unit,
    onFill: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    flag: TrayStockFlag = TrayStockFlag.OK,
    showFillHighlight: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val isDark = isSystemInDarkTheme()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                flag == TrayStockFlag.LOW -> StockOrange.copy(alpha = if (isDark) 0.14f else 0.10f)
                flag == TrayStockFlag.FILL && showFillHighlight ->
                    (if (isDark) VMflowBlueLight else VMflowBlue).copy(alpha = if (isDark) 0.12f else 0.07f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Product image
            ProductImage(
                imagePath = tray.products?.imagePath,
                contentDescription = tray.products?.name,
                size = 48.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Info column
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${tray.itemNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tray.products?.name ?: stringResource(R.string.tray_unassigned),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                StockBar(
                    current = tray.currentStock,
                    capacity = tray.capacity,
                    height = 6.dp,
                    showLabel = true
                )
                if (flag == TrayStockFlag.SLOT_EMPTY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tray_slot_empty_elsewhere),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Stock controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStockChange(-1)
                        },
                        modifier = Modifier.size(36.dp),
                        enabled = tray.currentStock > 0,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(18.dp))
                    }

                    Text(
                        text = "${tray.currentStock}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStockChange(1)
                        },
                        modifier = Modifier.size(36.dp),
                        enabled = tray.currentStock < tray.capacity,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(18.dp))
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onFill,
                        modifier = Modifier.size(28.dp),
                        enabled = tray.currentStock < tray.capacity
                    ) {
                        Icon(
                            Icons.Default.KeyboardDoubleArrowUp,
                            contentDescription = stringResource(R.string.tray_fill_action),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.tray_delete_action),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
