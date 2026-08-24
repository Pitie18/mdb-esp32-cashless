package xyz.vmflow.ui.refill

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.vmflow.models.LegacyRefillItem
import xyz.vmflow.models.LegacyRefillMachine
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import xyz.vmflow.ui.theme.StockYellow

@Composable
fun RefillStepContent(
    refillMachine: LegacyRefillMachine,
    machineProgress: String,
    progressFraction: Float,
    isSaving: Boolean,
    onUpdateFillAmount: (trayId: String, amount: Int) -> Unit,
    onFillTrayFull: (trayId: String) -> Unit,
    onFillAllTrays: () -> Unit,
    onNextMachine: () -> Unit,
    onSkipMachine: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxSize()) {
        // Machine header with progress
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = refillMachine.machine.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = machineProgress,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val animatedProgress by animateFloatAsState(
                    targetValue = progressFraction,
                    animationSpec = tween(300),
                    label = "tour_progress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    strokeCap = StrokeCap.Round
                )
            }
        }

        // "Refill All" button
        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onFillAllTrays()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp)
        ) {
            Icon(Icons.Default.Done, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refill All Trays", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tray list
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(
                items = refillMachine.items,
                key = { it.tray.id }
            ) { item ->
                RefillTrayCard(
                    item = item,
                    onUpdateAmount = { amount -> onUpdateFillAmount(item.tray.id, amount) },
                    onFillFull = { onFillTrayFull(item.tray.id) }
                )
            }
        }

        // Bottom action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSkipMachine()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Skip", style = MaterialTheme.typography.titleSmall)
            }
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNextMachine()
                },
                modifier = Modifier
                    .weight(2f)
                    .height(56.dp),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    Text("Saving...", style = MaterialTheme.typography.titleSmall)
                } else {
                    Text("Next Machine", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun RefillTrayCard(
    item: LegacyRefillItem,
    onUpdateAmount: (Int) -> Unit,
    onFillFull: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val currentStock = item.currentStock
    val targetStock = currentStock + item.fillAmount
    val capacity = item.tray.capacity
    val isFull = item.fillAmount >= item.maxFillAmount

    val currentPct = if (capacity > 0) currentStock.toFloat() / capacity else 0f
    val targetPct = if (capacity > 0) targetStock.toFloat() / capacity else 0f

    val currentColor by animateColorAsState(
        targetValue = when {
            currentPct <= 0f -> StockRed
            currentPct < 0.25f -> StockRed
            currentPct < 0.5f -> StockOrange
            currentPct < 0.75f -> StockYellow
            else -> StockGreen
        },
        label = "current_color"
    )
    val targetColor by animateColorAsState(
        targetValue = when {
            targetPct < 0.25f -> StockRed
            targetPct < 0.5f -> StockOrange
            targetPct < 0.75f -> StockYellow
            else -> StockGreen
        },
        label = "target_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Product info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProductImage(
                    imagePath = item.tray.products?.imagePath,
                    contentDescription = item.tray.products?.name,
                    size = 52.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.tray.products?.name ?: "Slot #${item.tray.itemNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Slot #${item.tray.itemNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFillFull()
                    },
                    label = {
                        Text(
                            if (isFull) "Full" else "Fill",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    enabled = !isFull
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Visual stock change bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$currentStock",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = currentColor,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "->",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$targetStock",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = targetColor,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Animated progress bar showing current -> target
                Box(modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                ) {
                    val animatedTarget by animateFloatAsState(
                        targetValue = targetPct.coerceIn(0f, 1f),
                        animationSpec = tween(400),
                        label = "target_bar"
                    )
                    // Target bar (lighter)
                    LinearProgressIndicator(
                        progress = { animatedTarget },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(5.dp)),
                        color = targetColor.copy(alpha = 0.4f),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )
                    // Current stock bar (solid)
                    val animatedCurrent by animateFloatAsState(
                        targetValue = currentPct.coerceIn(0f, 1f),
                        animationSpec = tween(400),
                        label = "current_bar"
                    )
                    LinearProgressIndicator(
                        progress = { animatedCurrent },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(5.dp)),
                        color = currentColor,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent,
                        strokeCap = StrokeCap.Round
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "/$capacity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fill amount controls - LARGE TOUCH TARGETS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onUpdateAmount(item.fillAmount - 1)
                    },
                    modifier = Modifier.size(48.dp),
                    enabled = item.fillAmount > 0,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Decrease",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "+${item.fillAmount}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.fillAmount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onUpdateAmount(item.fillAmount + 1)
                    },
                    modifier = Modifier.size(48.dp),
                    enabled = item.fillAmount < item.maxFillAmount,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Increase",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
