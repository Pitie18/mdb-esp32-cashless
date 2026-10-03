package xyz.vmflow.ui.warehouse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import xyz.vmflow.R
import xyz.vmflow.data.WarehouseIntakeLogic
import xyz.vmflow.models.IntakeEntry
import xyz.vmflow.models.Product
import xyz.vmflow.models.Supplier
import xyz.vmflow.ui.components.ProductImage
import xyz.vmflow.ui.components.QrScannerSheet
import xyz.vmflow.ui.machines.relativeTimeAgo
import xyz.vmflow.ui.theme.StockGreen
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** MDB barcode formats a delivery label is realistically printed in — mirrors Task 8's brief for the warehouse scanner. */
private val WAREHOUSE_BARCODE_FORMATS = setOf(
    Barcode.FORMAT_EAN_13,
    Barcode.FORMAT_EAN_8,
    Barcode.FORMAT_UPC_A,
    Barcode.FORMAT_UPC_E,
    Barcode.FORMAT_CODE_128,
    Barcode.FORMAT_CODE_39,
)

/**
 * Incoming (intake booking) tab: product picker, quantity expression field
 * with live preview, optional batch number, optional expiration date,
 * supplier autocomplete, a barcode-scan shortcut, and the last 10 intakes.
 * Mirrors iOS `incomingTab` + `intakeFormContent` (`WarehouseView.swift`).
 *
 * All intake-form field state (product selection, quantity text, batch
 * number, expiration date, supplier text, scanner visibility) is local to
 * this composable — per `WarehouseViewModel`'s own doc comment, that state
 * deliberately does not live on the ViewModel. Business logic (the actual
 * `bookIntake`/`lookupBarcode` network calls) is NOT performed here: it is
 * always routed back through [onSubmit]/[onLookupBarcode], which
 * `WarehouseScreen.kt` binds to the real `WarehouseViewModel` calls — the
 * same "tab never touches the ViewModel directly" split Task 9 established
 * for `WarehouseStockTab`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseIntakeTab(
    uiState: WarehouseUiState,
    onSubmit: (
        productId: String,
        quantityText: String,
        batchNumber: String?,
        expirationIso: String?,
        supplierName: String?
    ) -> Unit,
    onLookupBarcode: (barcode: String, onFound: (productId: String) -> Unit, onNotFound: () -> Unit) -> Unit,
) {
    var selectedProductId by remember { mutableStateOf<String?>(null) }
    var productSearchText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }
    var batchNumber by remember { mutableStateOf("") }
    var expirationIso by remember { mutableStateOf<String?>(null) }
    var supplierName by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var barcodeNotFound by remember { mutableStateOf(false) }
    var lastSeenBookedTick by remember { mutableStateOf(uiState.intakeBookedTick) }

    val selectedProduct = uiState.products.firstOrNull { it.id == selectedProductId }
    val evaluatedQuantity = remember(quantityText) { WarehouseIntakeLogic.evaluateQuantityExpression(quantityText) }
    val canSubmit = selectedProductId != null && evaluatedQuantity != null && !uiState.isBookingIntake

    // Reset the whole form only when the ViewModel signals that a booking's
    // underlying write actually succeeded (intakeBookedTick incremented) —
    // NOT based on uiState.error being null. The write can succeed while the
    // post-write refresh (recent intakes / product summaries) fails, which
    // leaves `error` non-null even though the batch + transaction rows
    // genuinely landed; keying off `error == null` used to leave the form
    // armed in that case, inviting an accidental duplicate resubmission of
    // a delivery that already landed. A booking whose write itself fails
    // never advances the tick, so the form still stays populated for retry.
    LaunchedEffect(uiState.intakeBookedTick) {
        if (uiState.intakeBookedTick != lastSeenBookedTick) {
            selectedProductId = null
            productSearchText = ""
            quantityText = ""
            batchNumber = ""
            expirationIso = null
            supplierName = ""
            barcodeNotFound = false
            lastSeenBookedTick = uiState.intakeBookedTick
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.warehouse_intake_form_header),
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            ProductPickerField(
                products = uiState.products,
                selectedProduct = selectedProduct,
                searchText = productSearchText,
                onSearchTextChange = { productSearchText = it },
                onSelect = { productId ->
                    selectedProductId = productId
                    productSearchText = ""
                    barcodeNotFound = false
                },
                onClear = { selectedProductId = null }
            )
        }

        item {
            Column {
                OutlinedButton(
                    onClick = {
                        barcodeNotFound = false
                        showScanner = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.warehouse_intake_scan_button))
                }
                if (barcodeNotFound) {
                    Text(
                        text = stringResource(R.string.warehouse_intake_scan_not_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            Column {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.warehouse_intake_quantity_label)) },
                    placeholder = { Text(stringResource(R.string.warehouse_intake_quantity_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (evaluatedQuantity != null && quantityText.any { it in "+-*/x×" }) {
                    Text(
                        text = stringResource(R.string.warehouse_intake_quantity_preview, evaluatedQuantity),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = batchNumber,
                onValueChange = { batchNumber = it },
                label = { Text(stringResource(R.string.warehouse_intake_batch_label)) },
                placeholder = { Text(stringResource(R.string.warehouse_intake_optional_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            ExpirationDateField(
                expirationIso = expirationIso,
                onExpirationChange = { expirationIso = it }
            )
        }

        item {
            SupplierField(
                value = supplierName,
                onValueChange = { supplierName = it },
                suppliers = uiState.suppliers
            )
        }

        item {
            Button(
                onClick = {
                    val productId = selectedProductId ?: return@Button
                    onSubmit(
                        productId,
                        quantityText,
                        batchNumber.trim().ifEmpty { null },
                        expirationIso,
                        supplierName.trim().ifEmpty { null }
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isBookingIntake) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.warehouse_intake_submit))
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.warehouse_intake_recent_header),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val recent = uiState.recentIntakes.take(10)
        if (recent.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.warehouse_intake_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(recent, key = { it.id }) { entry ->
                RecentIntakeRow(entry = entry)
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onResult = { code ->
                showScanner = false
                onLookupBarcode(
                    code,
                    { productId ->
                        selectedProductId = productId
                        barcodeNotFound = false
                    },
                    { barcodeNotFound = true }
                )
            },
            onDismiss = { showScanner = false },
            formats = WAREHOUSE_BARCODE_FORMATS,
            title = stringResource(R.string.warehouse_intake_scan_title),
            hint = stringResource(R.string.warehouse_intake_scan_hint)
        )
    }
}

/**
 * Product selection: a compact "selected" summary row once a product is
 * chosen, or a search field + bounded product list otherwise. Deliberately
 * not a dropdown — the brief calls out that the product list can be long.
 */
@Composable
private fun ProductPickerField(
    products: List<Product>,
    selectedProduct: Product?,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onSelect: (productId: String) -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.warehouse_intake_product_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        if (selectedProduct != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProductImage(imagePath = selectedProduct.imagePath, contentDescription = selectedProduct.name, size = 36.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selectedProduct.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.product_unnamed),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.warehouse_intake_product_change))
                }
            }
        } else {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                placeholder = { Text(stringResource(R.string.warehouse_stock_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))

            val filtered = remember(products, searchText) {
                if (searchText.isBlank()) {
                    products
                } else {
                    products.filter { (it.name ?: "").contains(searchText, ignoreCase = true) }
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.warehouse_intake_product_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                ) {
                    items(filtered, key = { it.id }) { product ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(product.id) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProductImage(imagePath = product.imagePath, contentDescription = product.name, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = product.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.product_unnamed),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expiration ("best before") date picker. Android-idiomatic Material3
 * `DatePickerDialog` rather than a port of iOS's masked text field — same
 * end result: a valid ISO `yyyy-MM-dd` or nothing, with a "not in the past"
 * lower bound enforced via [SelectableDates].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpirationDateField(expirationIso: String?, onExpirationChange: (String?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = expirationIso?.let { formatDisplayDate(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.warehouse_intake_expiration_label)) },
        placeholder = { Text(stringResource(R.string.warehouse_intake_optional_hint)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expirationIso != null) {
                    IconButton(onClick = { onExpirationChange(null) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.warehouse_intake_expiration_clear))
                    }
                }
                IconButton(onClick = { showDialog = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.warehouse_intake_expiration_label))
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    if (showDialog) {
        val zone = ZoneOffset.UTC
        val todayMillis = remember { LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() }
        val initialMillis = remember(expirationIso) {
            expirationIso?.let {
                runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
            }
        }
        val selectableDates = remember(todayMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMillis
            }
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = selectableDates
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                        onExpirationChange(date.toString())
                    }
                    showDialog = false
                }) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun formatDisplayDate(dateIso: String): String {
    return try {
        val date = LocalDate.parse(dateIso)
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault()))
    } catch (_: Exception) {
        dateIso
    }
}

/**
 * Supplier autocomplete: an editable exposed dropdown against
 * [uiState.suppliers][WarehouseUiState.suppliers], filtered as the user
 * types. Free text is always accepted — `bookIntake`/`resolveOrCreateSupplier`
 * on the ViewModel side creates a new supplier when the typed name doesn't
 * match an existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupplierField(
    value: String,
    onValueChange: (String) -> Unit,
    suppliers: List<Supplier>,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, suppliers) {
        if (value.isBlank()) suppliers else suppliers.filter { it.name.contains(value, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(stringResource(R.string.warehouse_intake_supplier_label)) },
            placeholder = { Text(stringResource(R.string.warehouse_intake_optional_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filtered.forEach { supplier ->
                DropdownMenuItem(
                    text = { Text(supplier.name) },
                    onClick = {
                        onValueChange(supplier.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** One recent-intake row: product image, name, quantity delta, supplier, relative time. */
@Composable
private fun RecentIntakeRow(entry: IntakeEntry) {
    val productName = entry.productName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.product_unnamed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProductImage(imagePath = entry.imagePath, contentDescription = productName, size = 36.dp)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = productName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(
                    text = relativeTimeAgo(entry.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!entry.supplierName.isNullOrBlank()) {
                    Text(
                        text = " · ${entry.supplierName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        Text(
            text = "+${entry.quantity}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = StockGreen
        )
    }
}
