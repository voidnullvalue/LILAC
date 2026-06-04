package com.wingware.nfcbatch

import android.content.ContentResolver
import android.net.Uri

object CsvLoader {
    data class LoadedCsv(
        val fileName: String,
        val rows: List<BatchRow>,
    )

    fun load(contentResolver: ContentResolver, uri: Uri, displayName: String): Result<LoadedCsv> {
        return runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Unable to open selected CSV")
            val records = parseCsv(text)
            require(records.isNotEmpty()) { "CSV is empty" }

            val headers = records.first().map { it.trim().removePrefix("\uFEFF") }
            val uriIndex = headers.indexOfFirst { it.equals("uri", ignoreCase = true) }
            require(uriIndex >= 0) { "CSV must include a uri column" }
            val idIndex = headers.indexOfFirst { it.equals("id", ignoreCase = true) }

            val rows = records.drop(1).mapIndexedNotNull { index, record ->
                val rowNumber = index + 2
                val rowUri = record.getOrNull(uriIndex)?.trim().orEmpty()
                if (record.all { it.isBlank() }) {
                    null
                } else {
                    require(rowUri.isNotBlank()) { "Missing uri at CSV row $rowNumber" }
                    BatchRow(
                        rowNumber = rowNumber,
                        id = idIndex.takeIf { it >= 0 }?.let { record.getOrNull(it)?.trim().orEmpty() }?.ifBlank { null },
                        uri = rowUri,
                    )
                }
            }
            require(rows.isNotEmpty()) { "CSV contains no data rows" }
            LoadedCsv(fileName = displayName, rows = rows)
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val char = text[i]
            when {
                inQuotes && char == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                char == '"' -> inQuotes = !inQuotes
                !inQuotes && char == ',' -> {
                    row.add(cell.toString())
                    cell.clear()
                }
                !inQuotes && (char == '\n' || char == '\r') -> {
                    row.add(cell.toString())
                    cell.clear()
                    rows.add(row.toList())
                    row.clear()
                    if (char == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                        i++
                    }
                }
                else -> cell.append(char)
            }
            i++
        }

        if (inQuotes) {
            error("CSV has an unterminated quoted field")
        }
        row.add(cell.toString())
        if (row.any { it.isNotEmpty() }) {
            rows.add(row.toList())
        }
        return rows
    }
}
