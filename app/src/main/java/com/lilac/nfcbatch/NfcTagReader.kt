package com.lilac.nfcbatch

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.util.Locale

enum class TagLockState {
    LockedReadOnly,
    WritableNotLocked,
    Unknown,
    Unsupported,
}

data class NfcReadResult(
    val uri: String?,
    val noUriReason: String?,   // non-null when uri is null and no read error occurred
    val lockState: TagLockState,
    val tagIdHex: String?,
    val tagTechs: List<String>,
    val ndefMaxSize: Int?,
    val ndefCurrentSize: Int?,
    val error: String?,         // non-null means the read itself failed
)

object NfcTagReader {

    // NDEF Well-Known URI prefix codes per NFC Forum URI Record Type Definition
    private val URI_PREFIXES = mapOf(
        0x00 to "",            0x01 to "http://www.",    0x02 to "https://www.",
        0x03 to "http://",     0x04 to "https://",       0x05 to "tel:",
        0x06 to "mailto:",     0x07 to "ftp://anonymous:anonymous@",
        0x08 to "ftp://ftp.",  0x09 to "ftps://",        0x0A to "sftp://",
        0x0B to "smb://",      0x0C to "nfs://",         0x0D to "ftp://",
        0x0E to "dav://",      0x0F to "news:",           0x10 to "telnet://",
        0x11 to "imap:",       0x12 to "rtsp://",         0x13 to "urn:",
        0x14 to "pop:",        0x15 to "sip:",            0x16 to "sips:",
        0x17 to "tftp:",       0x18 to "btspp://",        0x19 to "btl2cap://",
        0x1A to "btgoep://",   0x1B to "tcpobex://",      0x1C to "irdaobex://",
        0x1D to "file://",     0x1E to "urn:epc:id:",     0x1F to "urn:epc:tag:",
        0x20 to "urn:epc:pat:", 0x21 to "urn:epc:raw:",  0x22 to "urn:epc:",
        0x23 to "urn:nfc:",
    )

    // Mirrors NdefRecord.TNF_WELL_KNOWN / TNF_ABSOLUTE_URI / RTD_URI without Android dep.
    internal const val TNF_WELL_KNOWN: Short = 0x01
    internal const val TNF_ABSOLUTE_URI: Short = 0x03
    internal val RTD_URI_TYPE: ByteArray = byteArrayOf('U'.code.toByte()) // 0x55

    fun readTag(tag: Tag): NfcReadResult {
        val tagIdHex = tag.id?.joinToString("") { b ->
            String.format(Locale.US, "%02X", b.toInt() and 0xFF)
        }
        val tagTechs = tag.techList?.map { it.substringAfterLast('.') } ?: emptyList()

        val ndef = Ndef.get(tag) ?: return NfcReadResult(
            uri = null,
            noUriReason = "Tag is not NDEF-readable (unsupported tag type).",
            lockState = TagLockState.Unsupported,
            tagIdHex = tagIdHex,
            tagTechs = tagTechs,
            ndefMaxSize = null,
            ndefCurrentSize = null,
            error = null,
        )

        return try {
            ndef.connect()
            val lockState = if (ndef.isWritable) TagLockState.WritableNotLocked else TagLockState.LockedReadOnly
            val ndefMaxSize = ndef.maxSize
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            if (message == null) {
                NfcReadResult(
                    uri = null,
                    noUriReason = "No NDEF message found",
                    lockState = lockState,
                    tagIdHex = tagIdHex,
                    tagTechs = tagTechs,
                    ndefMaxSize = ndefMaxSize,
                    ndefCurrentSize = 0,
                    error = null,
                )
            } else {
                val ndefCurrentSize = message.toByteArray().size
                val uri = message.records.firstNotNullOfOrNull { record ->
                    decodeUriPayload(record.tnf, record.type, record.payload)
                }
                NfcReadResult(
                    uri = uri,
                    noUriReason = if (uri == null) "No URI found on tag" else null,
                    lockState = lockState,
                    tagIdHex = tagIdHex,
                    tagTechs = tagTechs,
                    ndefMaxSize = ndefMaxSize,
                    ndefCurrentSize = ndefCurrentSize,
                    error = null,
                )
            }
        } catch (e: Exception) {
            NfcReadResult(
                uri = null,
                noUriReason = null,
                lockState = TagLockState.Unknown,
                tagIdHex = tagIdHex,
                tagTechs = tagTechs,
                ndefMaxSize = null,
                ndefCurrentSize = null,
                error = "Read failed: ${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            runCatching { ndef.close() }
        }
    }

    fun decodeUriRecord(record: NdefRecord): String? =
        decodeUriPayload(record.tnf, record.type, record.payload)

    // Pure function — JVM-testable without Android hardware.
    // Handles TNF_WELL_KNOWN + RTD_URI (prefix-coded) and TNF_ABSOLUTE_URI (raw type bytes).
    fun decodeUriPayload(tnf: Short, typeBytes: ByteArray, payload: ByteArray): String? {
        return when (tnf) {
            TNF_WELL_KNOWN -> {
                if (typeBytes.contentEquals(RTD_URI_TYPE) && payload.isNotEmpty()) {
                    val prefixCode = payload[0].toInt() and 0xFF
                    val prefix = URI_PREFIXES.getOrElse(prefixCode) { "" }
                    val suffix = String(payload, 1, payload.size - 1, Charsets.UTF_8)
                    (prefix + suffix).takeIf { it.isNotBlank() }
                } else null
            }
            TNF_ABSOLUTE_URI -> {
                String(typeBytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }
}
