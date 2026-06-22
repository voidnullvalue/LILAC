package com.lilac.nfcbatch

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// App brand palette
private val WwNavy = Color(0xFF0A1628)
private val WwNavyMedium = Color(0xFF1A2744)
private val WwBlue = Color(0xFF3B6FCC)
private val WwDanger = Color(0xFFB71C1C)
private val WwTextOnDark = Color.White
private val WwLabelOnDark = Color(0xFFB0BCD4)

// Read-only mode palette (teal — distinct from blue write mode and red lock/danger)
private val WwTeal = Color(0xFF00838F)
private val WwTealDark = Color(0xFF006064)
private val WwTealLight = Color(0xFFE0F7FA)
private val WwTealLabel = Color(0xFF004D40)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val viewModel: BatchViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private val readerFlags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NFC_BARCODE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            val soundCue = remember { SoundCue(this@MainActivity) }
            DisposableEffect(Unit) {
                onDispose { soundCue.release() }
            }
            BatchWriterApp(
                viewModel = viewModel,
                soundCue = soundCue,
                nfcAvailable = nfcAdapter != null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateReaderMode(viewModel.uiState.value.readerActive)
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        val state = viewModel.uiState.value

        if (state.readOnlyModeEnabled) {
            val result = NfcTagReader.readTag(tag)
            runOnUiThread { viewModel.updateReadOnlyResult(result) }
            return
        }

        val row = state.currentRow ?: return
        val result = when (state.batchState) {
            BatchState.READY_TO_WRITE -> NfcWriter.writeUri(tag, row.uri, state.lockingEnabled)
            BatchState.READY_TO_VERIFY -> NfcWriter.verifyUri(tag, row.uri, state.expectedTagUid, state.lockingEnabled)
            else -> return
        }

        runOnUiThread {
            if (result.success) {
                when (state.batchState) {
                    BatchState.READY_TO_WRITE -> viewModel.markWriteSuccess(result.tagUid)
                    BatchState.READY_TO_VERIFY -> viewModel.markVerifySuccess()
                    else -> Unit
                }
            } else {
                viewModel.fail(result.message)
            }
        }
    }

    fun updateReaderMode(active: Boolean) {
        if (active) {
            nfcAdapter?.enableReaderMode(this, this, readerFlags, null)
        } else {
            nfcAdapter?.disableReaderMode(this)
        }
    }
}

@Composable
private fun BatchWriterApp(
    viewModel: BatchViewModel,
    soundCue: SoundCue,
    nfcAvailable: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val uiState by viewModel.uiState.collectAsState()
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val fileName = context.displayName(uri)
        CsvLoader.load(context.contentResolver, uri, fileName)
            .onSuccess { loaded -> viewModel.loadCsv(loaded.fileName, loaded.rows) }
            .onFailure { error ->
                soundCue.error()
                viewModel.fail(error.message ?: "Unable to load CSV")
            }
    }

    LaunchedEffect(uiState.readerActive) {
        activity?.updateReaderMode(uiState.readerActive)
    }

    // Sound cues for normal write/verify workflow
    LaunchedEffect(uiState.batchState, uiState.statusText, uiState.lastError) {
        when {
            uiState.lastError != null -> soundCue.error()
            uiState.batchState == BatchState.READY_TO_VERIFY && uiState.lastError == null -> soundCue.writeSuccess()
            uiState.statusText.startsWith("Verified") || uiState.batchState == BatchState.COMPLETE -> soundCue.verifySuccess()
        }
    }

    // Sound cues for read-only scans (fires on each new scan via counter)
    LaunchedEffect(uiState.readOnlyScanCount) {
        if (uiState.readOnlyScanCount > 0) {
            val result = uiState.readOnlyResult ?: return@LaunchedEffect
            if (result.error != null || result.lockState == TagLockState.Unsupported) {
                soundCue.error()
            } else {
                soundCue.readSuccess()
            }
        }
    }

    MaterialTheme {
        BatchWriterScreen(
            uiState = uiState,
            nfcAvailable = nfcAvailable,
            onSelectCsv = { csvPicker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel")) },
            onPauseResume = viewModel::togglePaused,
            onSkip = viewModel::skip,
            onBack = viewModel::back,
            onReset = viewModel::resetBatch,
            onToggleLocking = viewModel::toggleLockingEnabled,
            onToggleReadOnly = viewModel::toggleReadOnlyMode,
            onClearReadOnlyResult = viewModel::clearReadOnlyResult,
        )
    }
}

@Composable
private fun BatchWriterScreen(
    uiState: BatchUiState,
    nfcAvailable: Boolean,
    onSelectCsv: () -> Unit,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onToggleLocking: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onClearReadOnlyResult: () -> Unit,
) {
    Scaffold(
        topBar = { BrandedHeader() },
        bottomBar = {
            LockModeToggle(
                lockingEnabled = uiState.lockingEnabled,
                onToggleLocking = onToggleLocking,
                dimmed = uiState.readOnlyModeEnabled,
            )
        },
        containerColor = WwNavy,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!nfcAvailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WwDanger,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "NFC IS NOT AVAILABLE ON THIS DEVICE",
                        color = WwTextOnDark,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (uiState.readOnlyModeEnabled) {
                ReadOnlyModeBanner()
                ProgressCard(uiState = uiState)
                ReadOnlyResultSection(
                    result = uiState.readOnlyResult,
                    onClear = onClearReadOnlyResult,
                )
            } else {
                ProgressCard(uiState = uiState)

                EquipmentIdCard(equipmentId = uiState.currentRow?.let { it.equipmentId ?: "Unknown" })

                val row = uiState.currentRow
                if (row != null) {
                    StatusCard(title = "ID", value = row.id ?: "—")
                    StatusCard(title = "URI Payload", value = row.uri)
                }

                val actionLabel = when {
                    uiState.batchState == BatchState.READY_TO_WRITE && uiState.lockingEnabled ->
                        "Next action: WRITE + PERMANENTLY LOCK tag"
                    uiState.batchState == BatchState.READY_TO_WRITE ->
                        "Next action: Write URI to tag"
                    uiState.batchState == BatchState.READY_TO_VERIFY && uiState.lockingEnabled ->
                        "Next action: Verify URI and lock state"
                    uiState.batchState == BatchState.READY_TO_VERIFY ->
                        "Next action: Verify URI"
                    else -> null
                }
                if (actionLabel != null) {
                    StatusCard(
                        title = "Action",
                        value = actionLabel,
                        isError = uiState.lockingEnabled && uiState.batchState == BatchState.READY_TO_WRITE,
                    )
                }

                StatusCard(title = "Status", value = uiState.statusText)

                uiState.lastError?.let { error ->
                    StatusCard(title = "Error", value = error, isError = true)
                }

                Spacer(modifier = Modifier.height(4.dp))

                ActionControls(
                    uiState = uiState,
                    onSelectCsv = onSelectCsv,
                    onPauseResume = onPauseResume,
                    onSkip = onSkip,
                    onBack = onBack,
                    onReset = onReset,
                )
            }

            ReadOnlyModeToggle(
                enabled = uiState.readOnlyModeEnabled,
                onToggle = onToggleReadOnly,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReadOnlyModeBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WwTealDark,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "READ ONLY MODE ENABLED",
                color = WwTextOnDark,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Scanning tags only. No writes. No locking. CSV position will not change.",
                color = Color(0xFFB2EBF2),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadOnlyResultSection(result: NfcReadResult?, onClear: () -> Unit) {
    if (result == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WwNavyMedium,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "Scan a tag to read its contents.",
                color = WwLabelOnDark,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WwTealLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SCANNED TAG",
                    color = WwTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                )
                OutlinedButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WwTeal),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (result.error != null) {
                ReadOnlyField(label = "Read Error", value = result.error, isError = true)
            } else {
                // URI — most prominent
                val uriText = result.uri
                    ?: result.noUriReason
                    ?: "No NDEF message found"
                ReadOnlyField(label = "URI", value = uriText, prominent = result.uri != null)

                // Lock state
                val lockText = when (result.lockState) {
                    TagLockState.LockedReadOnly -> "LOCKED / READ-ONLY"
                    TagLockState.WritableNotLocked -> "WRITABLE / NOT LOCKED"
                    TagLockState.Unknown -> "LOCK STATE UNKNOWN"
                    TagLockState.Unsupported -> "NOT NDEF-READABLE"
                }
                ReadOnlyField(label = "Lock State", value = lockText)
            }

            // Optional tag details
            result.tagIdHex?.let { ReadOnlyField(label = "Tag ID (hex)", value = it) }
            if (result.tagTechs.isNotEmpty()) {
                ReadOnlyField(label = "Tag Tech", value = result.tagTechs.joinToString(", "))
            }
            if (result.ndefMaxSize != null && result.ndefCurrentSize != null) {
                ReadOnlyField(
                    label = "NDEF Size",
                    value = "${result.ndefCurrentSize} / ${result.ndefMaxSize} bytes used",
                )
            }
        }
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, prominent: Boolean = false, isError: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            color = if (isError) WwDanger else WwTealLabel,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (isError) WwDanger else WwNavy,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (prominent) 15.sp else 13.sp,
        )
    }
}

@Composable
private fun BrandedHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(WwNavy),
    ) {
        Image(
            painter = painterResource(id = R.drawable.wing),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(80.dp)
                .alpha(0.07f),
            contentScale = ContentScale.FillHeight,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "LILAC",
                modifier = Modifier.height(44.dp),
                contentScale = ContentScale.FillHeight,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Lightweight Internal Labeling",
                    color = WwTextOnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    text = "and Activation Console",
                    color = WwTextOnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    text = "NFC batch URI writer",
                    color = WwLabelOnDark,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(uiState: BatchUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WwNavyMedium),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Row ${uiState.displayIndex} of ${uiState.totalRows}",
                    color = WwTextOnDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    text = uiState.csvFileName ?: "No CSV loaded",
                    color = WwLabelOnDark,
                    fontSize = 12.sp,
                )
            }
            StateBadge(batchState = uiState.batchState, paused = uiState.paused)
        }
    }
}

@Composable
private fun StateBadge(batchState: BatchState, paused: Boolean) {
    val (label, badgeColor) = when {
        paused -> "PAUSED" to Color(0xFFFFA000)
        batchState == BatchState.NEED_CSV -> "NO CSV" to Color(0xFF757575)
        batchState == BatchState.READY_TO_WRITE -> "WRITE" to WwBlue
        batchState == BatchState.READY_TO_VERIFY -> "VERIFY" to Color(0xFF388E3C)
        batchState == BatchState.COMPLETE -> "COMPLETE" to Color(0xFF388E3C)
        else -> "—" to Color(0xFF757575)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = badgeColor,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EquipmentIdCard(equipmentId: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WwNavyMedium),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "EQUIPMENT ID",
                color = WwLabelOnDark,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = equipmentId ?: "—",
                color = WwTextOnDark,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 72.sp,
                textAlign = TextAlign.Center,
                lineHeight = 80.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, isError: Boolean = false) {
    val containerColor = if (isError) Color(0xFFFFEBEE) else Color.White
    val titleColor = if (isError) WwDanger else WwBlue
    val valueColor = if (isError) WwDanger else WwNavy
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = title.uppercase(),
                color = titleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = valueColor,
                fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun ActionControls(
    uiState: BatchUiState,
    onSelectCsv: () -> Unit,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onSelectCsv,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WwBlue),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Select CSV", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Button(
                onClick = onPauseResume,
                enabled = uiState.batchState != BatchState.NEED_CSV,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WwNavyMedium),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = if (uiState.paused) "Resume" else "Pause",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onSkip,
                enabled = uiState.batchState == BatchState.READY_TO_WRITE || uiState.batchState == BatchState.READY_TO_VERIFY,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WwNavyMedium),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Skip", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Button(
                onClick = onBack,
                enabled = uiState.totalRows > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WwNavyMedium),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Back", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WwLabelOnDark),
        ) {
            Text("Reset Batch", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ReadOnlyModeToggle(enabled: Boolean, onToggle: () -> Unit) {
    val bgColor = if (enabled) WwTeal else WwNavyMedium
    val label = if (enabled)
        "READ ONLY MODE: ON — Tap to disable"
    else
        "READ ONLY MODE: OFF — Tap to enable scan-only mode"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
    ) {
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = bgColor,
                contentColor = WwTextOnDark,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun LockModeToggle(
    lockingEnabled: Boolean,
    onToggleLocking: () -> Unit,
    dimmed: Boolean = false,
) {
    val bgColor = if (lockingEnabled && !dimmed) WwDanger else WwNavyMedium
    val label = if (lockingEnabled)
        "!! LOCKING ENABLED — TAGS WILL BE PERMANENTLY LOCKED\nTap to disable"
    else
        "WRITE ONLY — TAGS WILL NOT BE LOCKED\nTap to enable locking"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f),
        color = bgColor,
        shadowElevation = if (dimmed) 0.dp else 12.dp,
    ) {
        Button(
            onClick = onToggleLocking,
            enabled = !dimmed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = bgColor,
                contentColor = WwTextOnDark,
                disabledContainerColor = bgColor,
                disabledContentColor = WwTextOnDark,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

private fun Context.displayName(uri: Uri): String {
    val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index)
        }
    }
    return uri.lastPathSegment ?: "selected.csv"
}
