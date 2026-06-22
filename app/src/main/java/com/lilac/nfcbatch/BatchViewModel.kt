package com.lilac.nfcbatch

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BatchState {
    NEED_CSV,
    READY_TO_WRITE,
    READY_TO_VERIFY,
    COMPLETE,
}

@Immutable
data class BatchRow(
    val rowNumber: Int,
    val id: String?,
    val uri: String,
) {
    val equipmentId: String? = extractEquipmentId(uri)
}

@Immutable
data class BatchUiState(
    val batchState: BatchState = BatchState.NEED_CSV,
    val csvFileName: String? = null,
    val rows: List<BatchRow> = emptyList(),
    val currentIndex: Int = 0,
    val paused: Boolean = false,
    val lockingEnabled: Boolean = false,
    val statusText: String = "Select a CSV to begin.",
    val lastError: String? = null,
    val expectedTagUid: String? = null,
    val readOnlyModeEnabled: Boolean = false,
    val readOnlyResult: NfcReadResult? = null,
    val readOnlyScanCount: Int = 0,
) {
    val totalRows: Int get() = rows.size
    val currentRow: BatchRow? get() = rows.getOrNull(currentIndex)
    val displayIndex: Int get() = if (totalRows == 0) 0 else (currentIndex + 1).coerceAtMost(totalRows)
    val readerActive: Boolean get() = readOnlyModeEnabled ||
        (!paused && (batchState == BatchState.READY_TO_WRITE || batchState == BatchState.READY_TO_VERIFY))
}

class BatchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    fun loadCsv(fileName: String, rows: List<BatchRow>) {
        _uiState.update { current ->
            val equipmentId = rows.firstOrNull()?.equipmentId ?: "Unknown"
            BatchUiState(
                batchState = BatchState.READY_TO_WRITE,
                csvFileName = fileName,
                rows = rows,
                lockingEnabled = current.lockingEnabled,
                readOnlyModeEnabled = current.readOnlyModeEnabled,
                statusText = writeReadyStatus(1, rows.size, equipmentId, current.lockingEnabled),
            )
        }
    }

    fun fail(message: String) {
        _uiState.update { state ->
            state.copy(
                statusText = message,
                lastError = message,
            )
        }
    }

    fun markWriteSuccess(tagUid: String?) {
        _uiState.update { state ->
            val row = state.currentRow ?: return@update state
            val equipmentId = row.equipmentId ?: "Unknown"
            val statusText = if (state.lockingEnabled)
                "Wrote and locked row ${state.displayIndex} (Equipment ID: $equipmentId). Tap the same tag to verify URI and lock state."
            else
                "Wrote row ${state.displayIndex} (Equipment ID: $equipmentId). Tap the same tag again to verify."
            state.copy(
                batchState = BatchState.READY_TO_VERIFY,
                statusText = statusText,
                lastError = null,
                expectedTagUid = tagUid,
            )
        }
    }

    fun markVerifySuccess() {
        _uiState.update { state ->
            val nextIndex = state.currentIndex + 1
            if (nextIndex >= state.totalRows) {
                state.copy(
                    batchState = BatchState.COMPLETE,
                    currentIndex = state.totalRows,
                    statusText = "All ${state.totalRows} rows complete.",
                    lastError = null,
                    expectedTagUid = null,
                )
            } else {
                val nextEquipmentId = state.rows[nextIndex].equipmentId ?: "Unknown"
                val verifiedLabel = if (state.lockingEnabled) "Verified URI and lock." else "Verified."
                state.copy(
                    batchState = BatchState.READY_TO_WRITE,
                    currentIndex = nextIndex,
                    statusText = "$verifiedLabel ${writeReadyStatus(nextIndex + 1, state.totalRows, nextEquipmentId, state.lockingEnabled)}",
                    lastError = null,
                    expectedTagUid = null,
                )
            }
        }
    }

    fun togglePaused() {
        _uiState.update { state ->
            val paused = !state.paused
            state.copy(
                paused = paused,
                statusText = if (paused) "Paused. NFC taps are ignored." else resumeStatus(state),
            )
        }
    }

    fun toggleLockingEnabled() {
        _uiState.update { state ->
            val locking = !state.lockingEnabled
            val updated = state.copy(lockingEnabled = locking)
            updated.copy(
                statusText = if (state.paused) "Paused. NFC taps are ignored." else resumeStatus(updated),
            )
        }
    }

    fun skip() {
        _uiState.update { state ->
            if (state.batchState == BatchState.NEED_CSV || state.batchState == BatchState.COMPLETE) {
                state
            } else {
                val nextIndex = state.currentIndex + 1
                if (nextIndex >= state.totalRows) {
                    state.copy(
                        batchState = BatchState.COMPLETE,
                        currentIndex = state.totalRows,
                        statusText = "Skipped final row. Batch complete.",
                        expectedTagUid = null,
                    )
                } else {
                    val nextEquipmentId = state.rows[nextIndex].equipmentId ?: "Unknown"
                    state.copy(
                        batchState = BatchState.READY_TO_WRITE,
                        currentIndex = nextIndex,
                        statusText = "Skipped. ${writeReadyStatus(nextIndex + 1, state.totalRows, nextEquipmentId, state.lockingEnabled)}",
                        expectedTagUid = null,
                    )
                }
            }
        }
    }

    fun back() {
        _uiState.update { state ->
            if (state.totalRows == 0) {
                state
            } else {
                val previousIndex = (state.currentIndex - 1).coerceAtLeast(0)
                val prevEquipmentId = state.rows[previousIndex].equipmentId ?: "Unknown"
                state.copy(
                    batchState = BatchState.READY_TO_WRITE,
                    currentIndex = previousIndex,
                    statusText = "Ready to rewrite row ${previousIndex + 1} of ${state.totalRows} (Equipment ID: $prevEquipmentId). Tap an NFC tag.",
                    expectedTagUid = null,
                )
            }
        }
    }

    fun toggleReadOnlyMode() {
        _uiState.update { state ->
            state.copy(
                readOnlyModeEnabled = !state.readOnlyModeEnabled,
                readOnlyResult = null,
            )
        }
    }

    fun updateReadOnlyResult(result: NfcReadResult) {
        _uiState.update { state ->
            state.copy(
                readOnlyResult = result,
                readOnlyScanCount = state.readOnlyScanCount + 1,
            )
        }
    }

    fun clearReadOnlyResult() {
        _uiState.update { state -> state.copy(readOnlyResult = null) }
    }

    fun resetBatch() {
        _uiState.value = BatchUiState()
    }

    private fun resumeStatus(state: BatchUiState): String {
        val equipmentId = state.currentRow?.equipmentId ?: "Unknown"
        return when (state.batchState) {
            BatchState.NEED_CSV -> "Select a CSV to begin."
            BatchState.READY_TO_WRITE -> writeReadyStatus(state.displayIndex, state.totalRows, equipmentId, state.lockingEnabled)
            BatchState.READY_TO_VERIFY -> if (state.lockingEnabled)
                "Ready to verify URI and lock state for row ${state.displayIndex} (Equipment ID: $equipmentId). Tap the same tag."
            else
                "Ready to verify row ${state.displayIndex} (Equipment ID: $equipmentId). Tap the same tag again."
            BatchState.COMPLETE -> "All ${state.totalRows} rows complete."
        }
    }
}

private fun writeReadyStatus(rowNum: Int, totalRows: Int, equipmentId: String, lockingEnabled: Boolean): String {
    return if (lockingEnabled)
        "Ready to WRITE + PERMANENTLY LOCK row $rowNum of $totalRows (Equipment ID: $equipmentId). Tap an NFC tag."
    else
        "Ready to write row $rowNum of $totalRows (Equipment ID: $equipmentId). Tap an NFC tag."
}
