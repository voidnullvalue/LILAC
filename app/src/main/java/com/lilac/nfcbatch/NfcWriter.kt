package com.lilac.nfcbatch

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.Locale

object NfcWriter {
    data class Result(
        val success: Boolean,
        val tagUid: String? = null,
        val message: String,
    )

    fun writeUri(tag: Tag, uri: String, lockingEnabled: Boolean = false): Result {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        val tagUid = tag.uidString()
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return writeFormattedTag(ndef, message, tagUid, lockingEnabled)
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return writeBlankTag(formatable, message, tagUid, lockingEnabled)
        }

        return Result(success = false, tagUid = tagUid, message = "Unsupported tag: not NDEF or NDEF-formatable.")
    }

    fun verifyUri(tag: Tag, expectedUri: String, expectedUid: String?, lockingEnabled: Boolean = false): Result {
        val tagUid = tag.uidString()
        if (expectedUid != null && tagUid != null && !expectedUid.equals(tagUid, ignoreCase = true)) {
            return Result(success = false, tagUid = tagUid, message = "Different tag tapped. Expected UID $expectedUid but found $tagUid.")
        }

        val ndef = Ndef.get(tag) ?: return Result(
            success = false,
            tagUid = tagUid,
            message = "Unsupported tag: cannot read NDEF data for verification.",
        )

        return try {
            ndef.connect()
            val actualUri = (ndef.cachedNdefMessage ?: ndef.ndefMessage)
                ?.records
                ?.firstNotNullOfOrNull { record -> record.toUri()?.toString() }
            when {
                actualUri == null -> Result(false, tagUid, "No URI record found on tag.")
                actualUri != expectedUri -> Result(false, tagUid, "URI mismatch. Expected $expectedUri but found $actualUri.")
                lockingEnabled && ndef.isWritable -> Result(false, tagUid, "URI verified, but tag is still writable / not locked.")
                else -> Result(true, tagUid, if (lockingEnabled) "Verified URI and lock state." else "Verified URI.")
            }
        } catch (exception: Exception) {
            Result(false, tagUid, "Verify failed: ${exception.message ?: exception.javaClass.simpleName}")
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun writeFormattedTag(ndef: Ndef, message: NdefMessage, tagUid: String?, lockingEnabled: Boolean): Result {
        return try {
            ndef.connect()
            when {
                !ndef.isWritable -> Result(false, tagUid, "Tag is read-only.")
                ndef.maxSize < message.toByteArray().size -> Result(false, tagUid, "Tag is too small for this URI payload.")
                else -> {
                    ndef.writeNdefMessage(message)
                    if (lockingEnabled) {
                        val locked = try {
                            ndef.makeReadOnly()
                        } catch (ex: Exception) {
                            return Result(false, tagUid, "URI written but tag was NOT locked: ${ex.message ?: ex.javaClass.simpleName}")
                        }
                        if (!locked) {
                            return Result(false, tagUid, "URI written but tag was NOT locked: makeReadOnly() returned false.")
                        }
                    }
                    Result(true, tagUid, if (lockingEnabled) "Wrote URI and locked tag." else "Wrote URI.")
                }
            }
        } catch (exception: Exception) {
            Result(false, tagUid, "Write failed: ${exception.message ?: exception.javaClass.simpleName}")
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun writeBlankTag(formatable: NdefFormatable, message: NdefMessage, tagUid: String?, lockingEnabled: Boolean): Result {
        return try {
            formatable.connect()
            if (lockingEnabled) {
                formatable.formatReadOnly(message)
                Result(true, tagUid, "Formatted blank tag, wrote URI, and locked tag.")
            } else {
                formatable.format(message)
                Result(true, tagUid, "Formatted blank tag and wrote URI.")
            }
        } catch (exception: Exception) {
            Result(false, tagUid, "Format/write failed: ${exception.message ?: exception.javaClass.simpleName}")
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun Tag.uidString(): String? {
        return id?.joinToString(separator = "") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
    }
}
