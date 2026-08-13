package xyz.vmflow.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import xyz.vmflow.models.ActivityFeedItem
import xyz.vmflow.models.CashBookActivity
import xyz.vmflow.models.CashBookEntryType
import xyz.vmflow.models.IntakeGroup
import xyz.vmflow.models.RefillActivity
import xyz.vmflow.models.TourActivity
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.theme.StockOrange
import xyz.vmflow.ui.theme.StockRed
import java.text.NumberFormat

/**
 * Sticky-ish section header above each calendar day's rows, e.g. "Today · 3 entries".
 * [countRes] defaults to the dashboard activity feed's "entries" wording; pass a
 * different `<plurals>` (e.g. `dashboard_sales_count`) for other day-grouped feeds
 * that reuse this same header, like the machine detail Sales tab.
 */
@Composable
fun DaySectionHeader(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    countRes: Int = R.plurals.dashboard_entries_count,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = pluralStringResource(countRes, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dispatches one feed item to its type-specific row. */
@Composable
fun ActivityFeedRow(
    item: ActivityFeedItem,
    currencyFormat: NumberFormat,
    isExpanded: Boolean,
    onToggleExpanded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ActivityFeedItem.Sale -> SaleFeedRow(item, currencyFormat, modifier)
        is ActivityFeedItem.MachineRefilled ->
            RefillFeedRow(item.activity, isExpanded, modifier) { onToggleExpanded(item.id) }
        is ActivityFeedItem.TourStarted ->
            TourFeedRow(item.activity, isExpanded, modifier) { onToggleExpanded(item.id) }
        is ActivityFeedItem.StockIntake ->
            IntakeFeedRow(item.group, isExpanded, modifier) { onToggleExpanded(item.id) }
        is ActivityFeedItem.CashBookEntry ->
            CashBookFeedRow(item.activity, currencyFormat, isExpanded, modifier) { onToggleExpanded(item.id) }
    }
}

@Composable
private fun SaleFeedRow(item: ActivityFeedItem.Sale, currencyFormat: NumberFormat, modifier: Modifier = Modifier) {
    val sale = item.sale
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(
            imagePath = sale.productImagePath,
            contentDescription = null,
            size = 36.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sale.productName ?: "#${sale.sale.itemNumber ?: 0}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            sale.machineName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = currencyFormat.format(sale.sale.itemPrice),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatTimeOfDay(sale.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RefillFeedRow(
    activity: RefillActivity,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val subtitle = buildList {
        activity.userDisplay?.let { add(stringResource(R.string.dashboard_filled_by, it)) }
        add(pluralStringResource(R.plurals.dashboard_items_count, activity.totalAdded, activity.totalAdded))
    }.joinToString(" · ")
    GenericActivityRow(
        icon = Icons.Filled.Inventory2,
        tint = xyz.vmflow.ui.theme.OnlineGreen,
        title = activity.machineName,
        subtitle = subtitle,
        date = activity.createdAt,
        detailLines = activity.products.map { "${it.quantity}× ${it.name}" },
        isExpanded = isExpanded,
        onToggle = onToggle,
        modifier = modifier,
    )
}

@Composable
private fun TourFeedRow(
    activity: TourActivity,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val subtitle = buildList {
        activity.userDisplay?.let { add(it) }
        add(pluralStringResource(R.plurals.dashboard_machines_count, activity.machineCount, activity.machineCount))
        activity.warehouseName?.let { add(it) }
    }.joinToString(" · ")
    GenericActivityRow(
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        tint = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.dashboard_tour_started),
        subtitle = subtitle,
        date = activity.createdAt,
        detailLines = activity.machineNames,
        isExpanded = isExpanded,
        onToggle = onToggle,
        modifier = modifier,
    )
}

@Composable
private fun IntakeFeedRow(
    group: IntakeGroup,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val subtitle = buildList {
        group.userDisplay?.let { add(it) }
        add(pluralStringResource(R.plurals.dashboard_products_count, group.productCount, group.productCount))
        group.warehouseName?.let { add(it) }
    }.joinToString(" · ")
    GenericActivityRow(
        icon = Icons.Filled.MoveToInbox,
        tint = StockOrange,
        title = stringResource(R.string.dashboard_stock_intake),
        subtitle = subtitle,
        date = group.date,
        detailLines = group.products.map { "${it.quantity}× ${it.name}" },
        isExpanded = isExpanded,
        onToggle = onToggle,
        modifier = modifier,
    )
}

@Composable
private fun CashBookFeedRow(
    activity: CashBookActivity,
    currencyFormat: NumberFormat,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val (icon, tint) = cashBookIconAndTint(activity.type)
    val subtitle = buildList {
        activity.userDisplay?.let { add(it) }
        add(currencyFormat.format(activity.amount))
        activity.category?.takeIf { it.isNotEmpty() }?.let { add(cashBookCategoryLabel(it)) }
    }.joinToString(" · ")
    GenericActivityRow(
        icon = icon,
        tint = tint,
        title = cashBookTypeLabel(activity.type),
        subtitle = subtitle,
        date = activity.createdAt,
        detailLines = activity.note?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList(),
        isExpanded = isExpanded,
        onToggle = onToggle,
        modifier = modifier,
    )
}

@Composable
private fun cashBookIconAndTint(type: CashBookEntryType): Pair<ImageVector, Color> = when (type) {
    CashBookEntryType.INITIAL -> Icons.Filled.Flag to MaterialTheme.colorScheme.onSurfaceVariant
    CashBookEntryType.WITHDRAWAL -> Icons.Filled.AccountBalance to StockRed
    CashBookEntryType.CORRECTION -> Icons.Filled.Tune to StockOrange
    CashBookEntryType.PAYOUT -> Icons.Filled.ArrowCircleUp to MaterialTheme.colorScheme.primary
    CashBookEntryType.EXPENSE -> Icons.Filled.ShoppingCart to StockOrange
    CashBookEntryType.REVERSAL -> Icons.Filled.Replay to StockOrange
    CashBookEntryType.UNKNOWN -> Icons.Filled.AccountBalance to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun cashBookTypeLabel(type: CashBookEntryType): String = stringResource(
    when (type) {
        CashBookEntryType.INITIAL -> R.string.cash_book_type_initial
        CashBookEntryType.WITHDRAWAL -> R.string.cash_book_type_withdrawal
        CashBookEntryType.CORRECTION -> R.string.cash_book_type_correction
        CashBookEntryType.PAYOUT -> R.string.cash_book_type_payout
        CashBookEntryType.EXPENSE -> R.string.cash_book_type_expense
        CashBookEntryType.REVERSAL -> R.string.cash_book_type_reversal
        CashBookEntryType.UNKNOWN -> R.string.cash_book_type_unknown
    },
)

@Composable
private fun cashBookCategoryLabel(category: String): String {
    val res = when (category) {
        "rent" -> R.string.cash_book_category_rent
        "goods" -> R.string.cash_book_category_goods
        "cleaning" -> R.string.cash_book_category_cleaning
        "fees" -> R.string.cash_book_category_fees
        else -> R.string.cash_book_category_other
    }
    return stringResource(res)
}

/**
 * Non-sale feed row shared by refill/tour/intake/cash-book entries: tinted
 * icon circle, title, subtitle, time — visually in rhythm with
 * [SaleFeedRow]. Tapping toggles an inline detail list when one exists.
 * Mirrors `ActivityEventRow` in
 * `ios/VMflow/Views/Dashboard/DashboardView.swift`.
 */
@Composable
private fun GenericActivityRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    date: Instant,
    detailLines: List<String>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDetails = detailLines.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (hasDetails) it.clickable(onClick = onToggle) else it }
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatTimeOfDay(date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasDetails) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isExpanded) 180f else 0f),
                    )
                }
            }
        }
        if (isExpanded && hasDetails) {
            Column(modifier = Modifier.padding(start = 48.dp, top = 4.dp)) {
                detailLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTimeOfDay(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d:%02d".format(dt.hour, dt.minute, dt.second)
}
