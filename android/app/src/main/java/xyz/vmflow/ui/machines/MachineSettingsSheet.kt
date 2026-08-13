package xyz.vmflow.ui.machines

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import xyz.vmflow.R
import xyz.vmflow.data.ServerStoreHolder
import xyz.vmflow.data.applyGeocodedPlace
import xyz.vmflow.data.defaultPublicOrigin
import xyz.vmflow.data.fetchOneShotLocation
import xyz.vmflow.data.reverseGeocodeCoordinate
import xyz.vmflow.models.VendingMachineWithEmbedded

/** The Machine Settings sheet's save payload — one field per `vendingMachine` column it edits. */
data class MachineSettingsFields(
    val locationLat: Double?,
    val locationLon: Double?,
    val addressStreet: String?,
    val addressHouseNumber: String?,
    val addressPostalCode: String?,
    val addressCity: String?,
    val formattedAddress: String?,
    val countryCode: String?,
    val nayaxMachineId: String?,
    val publicListing: Boolean
)

/**
 * Nayax ID, country, GPS location + reverse geocoding, and the public
 * status-page link + QR code, opened from the gear toolbar icon on
 * `MachineDetailScreen`. Android counterpart of
 * `ios/VMflow/Views/Machines/MachineSettingsSheet.swift` — everything except
 * the interactive MapKit map (deliberately out of scope: no Maps SDK
 * dependency for this app). GPS and reverse geocoding are kept via the
 * platform `LocationManager`/`Geocoder`, same as iOS's own CoreLocation use,
 * just without a map to tap on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineSettingsSheet(
    machine: VendingMachineWithEmbedded,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: suspend (MachineSettingsFields) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var nayaxId by remember { mutableStateOf(machine.nayaxMachineId ?: "") }
    var countryCode by remember { mutableStateOf(machine.countryCode) }
    var locationLat by remember { mutableStateOf(machine.locationLat) }
    var locationLon by remember { mutableStateOf(machine.locationLon) }
    var addressStreet by remember { mutableStateOf(machine.addressStreet) }
    var addressHouseNumber by remember { mutableStateOf(machine.addressHouseNumber) }
    var addressPostalCode by remember { mutableStateOf(machine.addressPostalCode) }
    var addressCity by remember { mutableStateOf(machine.addressCity) }
    var formattedAddress by remember { mutableStateOf(machine.formattedAddress) }
    var publicListing by remember { mutableStateOf(machine.publicListing ?: false) }

    var isLocating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    // Best-effort public frontend origin, remembered once the user confirms
    // or corrects it — mirrors iOS's `@AppStorage("vmflow-public-frontend-origin")`.
    var publicOrigin by remember {
        mutableStateOf(
            PublicOriginStore.get(context)?.takeIf { it.isNotBlank() }
                ?: defaultPublicOrigin(ServerStoreHolder.instance.selectedServer.value.sanitizedUrl)
        )
    }
    LaunchedEffect(publicOrigin) {
        if (publicOrigin.isNotBlank()) PublicOriginStore.set(context, publicOrigin)
    }

    val publicUrl = remember(publicOrigin, machine.id) { "$publicOrigin/m/${machine.id}" }
    val qrBitmap = remember(publicListing, publicUrl) {
        if (publicListing && publicOrigin.isNotBlank()) generateQrBitmap(publicUrl) else null
    }

    fun performLocationFetch() {
        scope.launch {
            isLocating = true
            locationError = null
            permissionDenied = false
            val location = fetchOneShotLocation(context)
            if (location == null) {
                locationError = context.getString(R.string.machine_settings_location_error)
                isLocating = false
                return@launch
            }
            locationLat = location.latitude
            locationLon = location.longitude
            // Best-effort — the coordinate is kept even if reverse geocoding
            // fails or returns nothing, mirrors iOS's `try?` comment.
            val place = reverseGeocodeCoordinate(context, location.latitude, location.longitude)
            if (place != null) {
                val result = applyGeocodedPlace(place, countryCode)
                addressStreet = result.street
                addressHouseNumber = result.houseNumber
                addressPostalCode = result.postalCode
                addressCity = result.city
                countryCode = result.countryCode
                formattedAddress = result.formattedAddress
            }
            isLocating = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            performLocationFetch()
        } else {
            permissionDenied = true
        }
    }

    fun onUseLocationClick() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            performLocationFetch()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun onClearLocation() {
        locationLat = null
        locationLon = null
        addressStreet = null
        addressHouseNumber = null
        addressPostalCode = null
        addressCity = null
        formattedAddress = null
    }

    fun save() {
        scope.launch {
            isSaving = true
            saveFailed = false
            val trimmedNayax = nayaxId.trim()
            val ok = onSave(
                MachineSettingsFields(
                    locationLat = locationLat,
                    locationLon = locationLon,
                    addressStreet = addressStreet,
                    addressHouseNumber = addressHouseNumber,
                    addressPostalCode = addressPostalCode,
                    addressCity = addressCity,
                    formattedAddress = formattedAddress,
                    countryCode = countryCode,
                    nayaxMachineId = trimmedNayax.ifEmpty { null },
                    publicListing = publicListing
                )
            )
            isSaving = false
            if (ok) onDismiss() else saveFailed = true
        }
    }

    // Swipe-to-dismiss/scrim-tap is blocked while a save is in flight, same
    // intent as SendCreditSheet's isSending gate.
    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.machine_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                NayaxSection(nayaxId = nayaxId, onNayaxIdChange = { nayaxId = it })
            }
            item {
                CountrySection(countryCode = countryCode, onCountryCodeChange = { countryCode = it })
            }
            item {
                LocationSection(
                    hasCoordinate = locationLat != null && locationLon != null,
                    formattedAddress = formattedAddress,
                    isLocating = isLocating,
                    locationError = locationError,
                    permissionDenied = permissionDenied,
                    onUseLocation = { onUseLocationClick() },
                    onClearLocation = { onClearLocation() }
                )
            }
            item {
                PublicListingSection(
                    publicListing = publicListing,
                    onPublicListingChange = { publicListing = it },
                    publicOrigin = publicOrigin,
                    onPublicOriginChange = { publicOrigin = it },
                    publicUrl = publicUrl,
                    qrBitmap = qrBitmap
                )
            }
            if (saveFailed) {
                item {
                    Text(
                        text = errorMessage ?: stringResource(R.string.machine_settings_save_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { save() }, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }
        }
    }
}

// ─── Nayax ───────────────────────────────────────────────────────────────

@Composable
private fun NayaxSection(nayaxId: String, onNayaxIdChange: (String) -> Unit) {
    SectionCard(title = stringResource(R.string.machine_settings_section_nayax)) {
        OutlinedTextField(
            value = nayaxId,
            onValueChange = onNayaxIdChange,
            label = { Text(stringResource(R.string.machine_settings_nayax_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─── Country ─────────────────────────────────────────────────────────────

/** Native-language labels are proper nouns — left untranslated, same as iOS's own `countryOptions`. */
private data class CountryOption(val code: String, val label: String)

private val countryOptions = listOf(
    CountryOption("DE", "Deutschland"),
    CountryOption("AT", "Österreich"),
    CountryOption("CH", "Schweiz"),
    CountryOption("FR", "France"),
    CountryOption("IT", "Italia"),
    CountryOption("ES", "España"),
    CountryOption("NL", "Nederland"),
    CountryOption("BE", "Belgique"),
    CountryOption("PL", "Polska"),
    CountryOption("CZ", "Česko"),
    CountryOption("PT", "Portugal"),
    CountryOption("LU", "Luxembourg")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountrySection(countryCode: String?, onCountryCodeChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val countryLabel = stringResource(R.string.machine_settings_section_country)
    val noneLabel = stringResource(R.string.machine_settings_country_none)
    val selectedLabel = countryOptions.find { it.code == countryCode }?.label ?: noneLabel

    SectionCard(title = countryLabel) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(countryLabel) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        onCountryCodeChange(null)
                        expanded = false
                    }
                )
                countryOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onCountryCodeChange(option.code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// ─── Location ────────────────────────────────────────────────────────────

@Composable
private fun LocationSection(
    hasCoordinate: Boolean,
    formattedAddress: String?,
    isLocating: Boolean,
    locationError: String?,
    permissionDenied: Boolean,
    onUseLocation: () -> Unit,
    onClearLocation: () -> Unit
) {
    SectionCard(title = stringResource(R.string.machine_settings_section_location)) {
        if (!formattedAddress.isNullOrBlank()) {
            Text(
                text = formattedAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onUseLocation, enabled = !isLocating) {
                if (isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.machine_settings_use_location))
                }
            }
            if (hasCoordinate) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onClearLocation) {
                    Text(stringResource(R.string.machine_settings_clear_location))
                }
            }
        }
        if (permissionDenied) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.machine_settings_location_permission_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (locationError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = locationError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.machine_settings_location_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Public status page ─────────────────────────────────────────────────

@Composable
private fun PublicListingSection(
    publicListing: Boolean,
    onPublicListingChange: (Boolean) -> Unit,
    publicOrigin: String,
    onPublicOriginChange: (String) -> Unit,
    publicUrl: String,
    qrBitmap: ImageBitmap?
) {
    SectionCard(title = stringResource(R.string.machine_settings_section_public)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.machine_settings_public_toggle), modifier = Modifier.weight(1f))
            Switch(checked = publicListing, onCheckedChange = onPublicListingChange)
        }
        if (publicListing) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = publicOrigin,
                onValueChange = onPublicOriginChange,
                label = { Text(stringResource(R.string.machine_settings_public_origin_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            if (qrBitmap != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = stringResource(R.string.machine_settings_qr_content_description),
                        modifier = Modifier.size(160.dp),
                        filterQuality = FilterQuality.None
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = publicUrl,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.machine_settings_public_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Shared building blocks ──────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** Renders [text] as a QR code, or `null` if it can't be encoded (e.g. empty string). */
private fun generateQrBitmap(text: String, size: Int = 512): ImageBitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap.asImageBitmap()
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Minimal single-key persistence for the corrected public-origin guess — proportionate for one string, no need for the fuller `ServerStore`/`KeyValueStore` abstraction. */
private object PublicOriginStore {
    private const val PREFS = "vmflow_machine_settings"
    private const val KEY = "vmflow-public-frontend-origin"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}
