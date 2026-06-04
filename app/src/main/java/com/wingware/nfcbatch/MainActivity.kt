package com.wingware.nfcbatch

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
            val soundCue = remember { SoundCue() }
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
        val row = state.currentRow ?: return
        val result = when (state.batchState) {
            BatchState.READY_TO_WRITE -> NfcWriter.writeUri(tag, row.uri)
            BatchState.READY_TO_VERIFY -> NfcWriter.verifyUri(tag, row.uri, state.expectedTagUid)
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
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
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

    LaunchedEffect(uiState.batchState, uiState.statusText, uiState.lastError) {
        when {
            uiState.lastError != null -> soundCue.error()
            uiState.batchState == BatchState.READY_TO_VERIFY && uiState.lastError == null -> soundCue.writeSuccess()
            uiState.statusText.startsWith("Verified") || uiState.batchState == BatchState.COMPLETE -> soundCue.verifySuccess()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            BatchWriterScreen(
                uiState = uiState,
                nfcAvailable = nfcAvailable,
                onSelectCsv = { csvPicker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel")) },
                onPauseResume = viewModel::togglePaused,
                onSkip = viewModel::skip,
                onBack = viewModel::back,
                onReset = viewModel::resetBatch,
            )
        }
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
) {
    val row = uiState.currentRow
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NFC CSV Batch URI Writer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!nfcAvailable) {
            Text("NFC is not available on this device.", color = Color.Red, fontWeight = FontWeight.Bold)
        }

        StatusCard(title = "CSV", value = uiState.csvFileName ?: "No CSV selected")
        StatusCard(title = "State", value = uiState.batchState.name)
        StatusCard(title = "Progress", value = "Row ${uiState.displayIndex} of ${uiState.totalRows}")
        StatusCard(title = "Current ID", value = row?.id ?: "—")
        StatusCard(title = "Current URI", value = row?.uri ?: "—")
        StatusCard(title = "Status", value = uiState.statusText)
        uiState.lastError?.let { error -> StatusCard(title = "Last Error", value = error, isError = true) }

        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSelectCsv, modifier = Modifier.weight(1f)) { Text("Select CSV") }
            Button(onClick = onPauseResume, enabled = uiState.batchState != BatchState.NEED_CSV, modifier = Modifier.weight(1f)) {
                Text(if (uiState.paused) "Resume" else "Pause")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSkip, enabled = uiState.batchState == BatchState.READY_TO_WRITE || uiState.batchState == BatchState.READY_TO_VERIFY, modifier = Modifier.weight(1f)) { Text("Skip") }
            Button(onClick = onBack, enabled = uiState.totalRows > 0, modifier = Modifier.weight(1f)) { Text("Back") }
        }
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset Batch") }
    }
}

@Composable
private fun StatusCard(title: String, value: String, isError: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(value, color = if (isError) Color.Red else Color.Unspecified)
        }
    }
}

private fun Context.displayName(uri: Uri): String {
    val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return it.getString(index)
            }
        }
    }
    return uri.lastPathSegment ?: "selected.csv"
}
