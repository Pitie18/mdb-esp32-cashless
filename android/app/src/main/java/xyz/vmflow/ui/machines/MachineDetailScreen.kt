package xyz.vmflow.ui.machines

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import xyz.vmflow.R
import xyz.vmflow.data.SalesFeed
import xyz.vmflow.data.SalesFeedDayGroup
import xyz.vmflow.data.SalesFeedItem
import xyz.vmflow.models.Sale
import xyz.vmflow.models.SuppressedSale
import xyz.vmflow.models.Tray
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.components.StatusChip
import xyz.vmflow.ui.dashboard.DaySectionHeader
import xyz.vmflow.ui.trays.TrayListContent
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineDetailScreen(
    machineId: String,
    onNavigateBack: () -> Unit,
    viewModel: MachineDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY).apply {
        currency = java.util.Currency.getInstance("EUR")
    }

    LaunchedEffect(machineId) {
        viewModel.loadMachine(machineId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.machineStats?.machine?.displayName ?: "Machine",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    uiState.machineStats?.let {
                        StatusChip(
                            isOnline = it.machine.isOnline,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading && uiState.machineStats == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Tabs
                    val tabs = listOf("Overview", "Trays", "Sales", stringResource(R.string.analysis_tab_title))
                    TabRow(selectedTabIndex = uiState.selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = uiState.selectedTab == index,
                                onClick = { viewModel.selectTab(index) },
                                text = { Text(title) }
                            )
                        }
                    }

                    when (uiState.selectedTab) {
                        0 -> OverviewTab(uiState, currencyFormat)
                        1 -> TrayListContent(
                            trays = uiState.machineStats?.trays ?: emptyList(),
                            products = uiState.products,
                            machineId = machineId,
                            onStockChange = { trayId, delta ->
                                viewModel.updateTrayStock(trayId, delta)
                            },
                            onFillTray = { trayId ->
                                viewModel.fillTray(trayId)
                            },
                            onDeleteTray = { trayId ->
                                viewModel.deleteTray(trayId)
                            },
                            onTraysChanged = { viewModel.refresh() }
                        )
                        2 -> SalesTab(
                            uiState = uiState,
                            currencyFormat = currencyFormat,
                            onRestoreSuppressed = { suppressedId ->
                                viewModel.restoreSuppressedSale(suppressedId)
                            }
                        )
                        3 -> MachineAnalysisTab(
                            machineId = machineId,
                            trays = uiState.machineStats?.trays ?: emptyList(),
                            catalogue = uiState.products,
                            onSwapApplied = { viewModel.refresh() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(
    uiState: MachineDetailUiState,
    currencyFormat: NumberFormat
) {
    val stats = uiState.machineStats ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Revenue card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Revenue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Today",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                currencyFormat.format(stats.todayRevenue),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text(
                                "Yesterday",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                currencyFormat.format(stats.yesterdayRevenue),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Stats cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Sales Today",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${stats.todaySalesCount}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Card(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Visitors",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${stats.paxCount}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Device info
        item {
            stats.machine.embeddeds?.let { embedded ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Device Info",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DeviceInfoRow("Status", if (embedded.isOnline) "Online" else "Offline")
                        embedded.macAddress?.let { DeviceInfoRow("MAC", it) }
                        embedded.firmwareVersion?.let { DeviceInfoRow("Firmware", it) }
                        embedded.subdomain?.let { DeviceInfoRow("Subdomain", it.toString()) }
                    }
                }
            }
        }

        // Stock summary
        item {
            if (stats.trays.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Stock Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val totalCapacity = stats.trays.sumOf { it.capacity }
                        val totalStock = stats.trays.sumOf { it.currentStock }
                        val emptyTrays = stats.trays.count { it.isCritical }
                        val lowTrays = stats.trays.count { it.isLow && !it.isCritical }

                        DeviceInfoRow("Total Stock", "$totalStock / $totalCapacity")
                        DeviceInfoRow("Trays", "${stats.trays.size}")
                        if (emptyTrays > 0) DeviceInfoRow("Empty Trays", "$emptyTrays")
                        if (lowTrays > 0) DeviceInfoRow("Low Trays", "$lowTrays")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Day-grouped sales feed. Real sales and suppressed (auto-removed brownout
 * duplicate) sales are merged into one list via [SalesFeed], each day's
 * header showing only the real-sale count. Mirrors iOS's `salesTab`
 * (MachineDetailView.swift ~L350-410): the empty state only considers real
 * sales, same as iOS checking `viewModel.recentSales.isEmpty`.
 */
@Composable
private fun SalesTab(
    uiState: MachineDetailUiState,
    currencyFormat: NumberFormat,
    onRestoreSuppressed: (String) -> Unit
) {
    if (uiState.sales.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sales_tab_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val zone = remember { TimeZone.currentSystemDefault() }
    val trays = uiState.machineStats?.trays ?: emptyList()
    var pendingRestore by remember { mutableStateOf<SuppressedSale?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Plain computation, not `remember` — this content lambda is
        // `LazyListScope.() -> Unit`, not a @Composable context (same
        // constraint as the dashboard activity feed in DashboardScreen.kt).
        val groups = SalesFeed.groupByDay(SalesFeed.buildItems(uiState.sales, uiState.suppressedSales), zone)
        groups.forEach { group ->
            item(key = "day-${group.date}") {
                DaySectionHeader(
                    label = dayLabel(group, zone),
                    count = group.saleCount,
                    countRes = R.plurals.dashboard_sales_count,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(items = group.items, key = { it.id }) { feedItem ->
                when (feedItem) {
                    is SalesFeedItem.SaleRow -> SaleFeedCard(
                        sale = feedItem.sale,
                        instant = feedItem.date,
                        trays = trays,
                        zone = zone,
                        currencyFormat = currencyFormat
                    )
                    is SalesFeedItem.SuppressedRow -> SuppressedSaleCard(
                        sale = feedItem.suppressed,
                        instant = feedItem.date,
                        trays = trays,
                        zone = zone,
                        currencyFormat = currencyFormat,
                        isAdmin = uiState.isAdmin,
                        onRestoreClick = { pendingRestore = feedItem.suppressed }
                    )
                }
            }
        }
    }

    pendingRestore?.let { sale ->
        RestoreSaleDialog(
            onConfirm = {
                onRestoreSuppressed(sale.id)
                pendingRestore = null
            },
            onDismiss = { pendingRestore = null }
        )
    }
}

/**
 * "Today" / "Yesterday" / locale-formatted weekday+date, matching iOS
 * `dayLabel(for:)`. Mirrors `dayLabel(group: FeedDayGroup)` in
 * `DashboardScreen.kt` — same day-header formatting rule, ported to this
 * screen's own feed-group type.
 */
@Composable
private fun dayLabel(group: SalesFeedDayGroup, zone: TimeZone): String {
    val today = remember(zone) { Clock.System.now().toLocalDateTime(zone).date }
    val yesterday = remember(today) { today.minus(1, DateTimeUnit.DAY) }
    return when (group.date) {
        today -> stringResource(R.string.dashboard_day_today)
        yesterday -> stringResource(R.string.dashboard_day_yesterday)
        else -> {
            // java.time is available unconditionally from minSdk 26 up, no
            // core-library desugaring needed — locale-aware weekday/month
            // formatting without hand-rolling a formatter table.
            val javaDate = java.time.LocalDate.of(group.date.year, group.date.monthNumber, group.date.dayOfMonth)
            val formatter = java.time.format.DateTimeFormatter.ofPattern(
                "EEEE, d MMMM",
                Locale.getDefault()
            )
            javaDate.format(formatter)
        }
    }
}

private fun formatTimeOfDay(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    return "%02d:%02d:%02d".format(local.hour, local.minute, local.second)
}

/** Resolves a sale/suppressed row's product name via its own snapshot, then a tray lookup by slot. */
@Composable
private fun resolveProductName(itemNumber: Int?, snapshotName: String?, trays: List<Tray>): String {
    snapshotName?.let { return it }
    val tray = itemNumber?.let { num -> trays.find { it.itemNumber == num } }
    tray?.products?.name?.let { return it }
    return itemNumber?.let { stringResource(R.string.sales_item_slot, it) } ?: stringResource(R.string.sales_item_generic)
}

private fun resolveProductImage(itemNumber: Int?, snapshotImage: String?, trays: List<Tray>): String? {
    snapshotImage?.let { return it }
    val tray = itemNumber?.let { num -> trays.find { it.itemNumber == num } }
    return tray?.products?.imagePath
}

@Composable
private fun SaleFeedCard(
    sale: Sale,
    instant: Instant,
    trays: List<Tray>,
    zone: TimeZone,
    currencyFormat: NumberFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductImage(
                imagePath = resolveProductImage(sale.itemNumber, sale.products?.imagePath, trays),
                contentDescription = null,
                size = 44.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resolveProductName(sale.itemNumber, sale.products?.name, trays),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    sale.channel?.let { ch ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = ch,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = currencyFormat.format(sale.itemPrice),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatTimeOfDay(instant, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A suppressed (auto-removed brownout duplicate) sale: muted, struck-through
 * price, "Auto-removed" badge, reason text — never counted in the day header
 * and, unlike a real sale row, offers a restore affordance (admin-only).
 * Mirrors iOS `SuppressedSaleListRow`.
 */
@Composable
private fun SuppressedSaleCard(
    sale: SuppressedSale,
    instant: Instant,
    trays: List<Tray>,
    zone: TimeZone,
    currencyFormat: NumberFormat,
    isAdmin: Boolean,
    onRestoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.7f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductImage(
                imagePath = resolveProductImage(sale.itemNumber, sale.products?.imagePath, trays),
                contentDescription = null,
                size = 44.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resolveProductName(sale.itemNumber, sale.products?.name, trays),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.suppressed_badge_auto_removed),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = suppressedReasonText(sale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = sale.itemPrice?.let { currencyFormat.format(it) } ?: "--",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough
                )
                Text(
                    text = formatTimeOfDay(instant, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isAdmin) {
                IconButton(onClick = onRestoreClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.restore_sale_action),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** Clock fragment + gap-or-near-duplicate fragment, joined with " · ". Mirrors `SuppressedSale.reasonText` on iOS. */
@Composable
private fun suppressedReasonText(sale: SuppressedSale): String {
    val clockFragment = if (sale.deviceCreatedAt == null) {
        stringResource(R.string.suppressed_reason_no_clock)
    } else {
        stringResource(R.string.suppressed_reason_clock_unsynced)
    }
    val gapSeconds = SalesFeed.suppressedGapSeconds(sale)
    val detailFragment = if (gapSeconds != null) {
        stringResource(R.string.suppressed_reason_gap, gapSeconds)
    } else {
        stringResource(R.string.suppressed_reason_near_duplicate)
    }
    return "$clockFragment · $detailFragment"
}

@Composable
private fun RestoreSaleDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_sale_dialog_title)) },
        text = { Text(stringResource(R.string.restore_sale_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.restore_sale_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
