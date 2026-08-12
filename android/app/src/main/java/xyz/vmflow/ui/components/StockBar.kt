package xyz.vmflow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.ui.theme.StockGreen
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import xyz.vmflow.ui.theme.StockYellow

@Composable
fun StockBar(
    current: Int,
    capacity: Int,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    showLabel: Boolean = true,
    animationDurationMs: Int = 600
) {
    val percentage = if (capacity > 0) current.toFloat() / capacity.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = percentage.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = animationDurationMs),
        label = "stock_progress"
    )
    val color by animateColorAsState(
        targetValue = when {
            percentage <= 0f -> StockRed
            percentage < 0.25f -> StockRed
            percentage < 0.5f -> StockOrange
            percentage < 0.75f -> StockYellow
            else -> StockGreen
        },
        animationSpec = tween(durationMillis = animationDurationMs),
        label = "stock_color"
    )

    val description = stringResource(R.string.stock_bar_description, current, capacity)

    Column(
        // mergeDescendants collapses the bar and its optional label into a
        // single node, and the explicit description replaces "4 slash 10"
        // with something a screen reader can actually convey.
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        }
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                Text(
                    text = "$current / $capacity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun StockHealthBar(
    trays: List<xyz.vmflow.models.Tray>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp
) {
    val totalCapacity = trays.sumOf { it.capacity }
    val totalStock = trays.sumOf { it.currentStock }

    StockBar(
        current = totalStock,
        capacity = totalCapacity,
        modifier = modifier,
        height = height,
        showLabel = false
    )
}
