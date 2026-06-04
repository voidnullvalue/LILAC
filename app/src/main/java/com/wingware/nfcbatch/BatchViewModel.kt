package com.wingware.nfcbatch

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
)

@Immutable
data class BatchUiState(
    val batchState: BatchState = BatchState.NEED_CSV,
    val csvFileName: String? = null,
    val rows: List<BatchRow> = emptyList(),
    val currentIndex: Int = 0,
    val paused: Boolean = false,
    val statusText: String = "Select a CSV to begin.",
    val lastError: String? = null,
    val expectedTagUid: String? = null,
) {
    val totalRows: Int get() = rows.size
    val currentRow: BatchRow? get() = rows.getOrNull(currentIndex)
    val displayIndex: Int get() = if (totalRows == 0) 0 else (currentIndex + 1).coerceAtMost(totalRows)
    val readerActive: Boolean get() = !paused && (batchState == BatchState.READY_TO_WRITE || batchState == BatchState.READY_TO_VERIFY)
}

class BatchViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    fun loadCsv(fileName: String, rows: List<BatchRow>) {
        _uiState.value = BatchUiState(
            batchState = BatchState.READY_TO_WRITE,
            csvFileName = fileName,
            rows = rows,
            statusText = "Ready to write row 1 of ${rows.size}. Tap an NFC tag.",
        )
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
            state.copy(
                batchState = BatchState.READY_TO_VERIFY,
                statusText = "Wrote row ${state.displayIndex}. Tap the same tag again to verify.",
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
                state.copy(
                    batchState = BatchState.READY_TO_WRITE,
                    currentIndex = nextIndex,
                    statusText = "Verified. Ready to write row ${nextIndex + 1} of ${state.totalRows}.",
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
                    state.copy(
                        batchState = BatchState.READY_TO_WRITE,
                        currentIndex = nextIndex,
                        statusText = "Skipped. Ready to write row ${nextIndex + 1} of ${state.totalRows}.",
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
                state.copy(
                    batchState = BatchState.READY_TO_WRITE,
                    currentIndex = previousIndex,
                    statusText = "Ready to rewrite row ${previousIndex + 1} of ${state.totalRows}.",
                    expectedTagUid = null,
                )
            }
        }
    }

    fun resetBatch() {
        _uiState.value = BatchUiState()
    }

    private fun resumeStatus(state: BatchUiState): String {
        return when (state.batchState) {
            BatchState.NEED_CSV -> "Select a CSV to begin."
            BatchState.READY_TO_WRITE -> "Ready to write row ${state.displayIndex} of ${state.totalRows}. Tap an NFC tag."
            BatchState.READY_TO_VERIFY -> "Ready to verify row ${state.displayIndex}. Tap the same tag again."
            BatchState.COMPLETE -> "All ${state.totalRows} rows complete."
        }
    }
}
