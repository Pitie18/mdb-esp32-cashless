package xyz.vmflow.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DayOfWeek
import xyz.vmflow.R
import xyz.vmflow.data.DailySales

/**
 * 31-day revenue bar chart. Deliberately a plain Compose `Canvas`, not a
 * charting library — Swift Charts (`BarMark`/`RuleMark`) has no Compose
 * counterpart worth pulling in a new dependency for, and phase 2's own
 * conversion rule forbids adding one. Weekend bars render lighter, and a
 * dashed line marks the period average — mirrors the two `ForEach`/`RuleMark`
 * layers of `chartSection` in `ios/VMflow/Views/Dashboard/DashboardView.swift`,
 * minus the drag-to-scrub tooltip (out of scope for this port).
 */
@Composable
fun DashboardBarChart(
    days: List<DailySales>,
    average: Double,
    modifier: Modifier = Modifier,
) {
    val hasData = days.any { it.count > 0 }
    if (!hasData) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.dashboard_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val barColor = MaterialTheme.colorScheme.primary
    val weekendBarColor = barColor.copy(alpha = 0.45f)
    val averageColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val maxRevenue = (days.maxOfOrNull { it.revenue } ?: 0.0).coerceAtLeast(average)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        if (maxRevenue <= 0.0 || days.isEmpty()) return@Canvas

        val labelHeight = 16.dp.toPx()
        val chartHeight = size.height - labelHeight
        val barSlot = size.width / days.size
        val barWidth = (barSlot * 0.6f).coerceAtLeast(1f)

        // Bars
        days.forEachIndexed { index, day ->
            val barHeight = (day.revenue / maxRevenue * chartHeight).toFloat().coerceAtLeast(0f)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val left = index * barSlot + (barSlot - barWidth) / 2f
            drawRect(
                color = if (isWeekend) weekendBarColor else barColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, chartHeight - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }

        // Average line (dashed)
        if (average > 0.0) {
            val y = chartHeight - (average / maxRevenue * chartHeight).toFloat()
            drawLine(
                color = averageColor,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }

        // Day-of-month labels every ~7 days
        days.forEachIndexed { index, day ->
            if (index % 7 == 0 || index == days.lastIndex) {
                val label = textMeasurer.measure(
                    text = "${day.date.dayOfMonth}",
                    style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = axisColor),
                )
                val centerX = index * barSlot + barSlot / 2f
                drawText(
                    textLayoutResult = label,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        centerX - label.size.width / 2f,
                        chartHeight + 2.dp.toPx(),
                    ),
                )
            }
        }

        // Baseline
        drawLine(
            color = axisColor.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, chartHeight),
            end = androidx.compose.ui.geometry.Offset(size.width, chartHeight),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
