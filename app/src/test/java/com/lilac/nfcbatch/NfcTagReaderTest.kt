package com.lilac.nfcbatch

import com.lilac.nfcbatch.NfcTagReader.TNF_ABSOLUTE_URI
import com.lilac.nfcbatch.NfcTagReader.TNF_WELL_KNOWN
import com.lilac.nfcbatch.NfcTagReader.RTD_URI_TYPE
import com.lilac.nfcbatch.NfcTagReader.decodeUriPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcTagReaderTest {

    private fun wellKnownUriPayload(prefixCode: Int, suffix: String): ByteArray {
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
        return byteArrayOf(prefixCode.toByte()) + suffixBytes
    }

    // --- TNF_WELL_KNOWN + RTD_URI ---

    @Test
    fun `decodes well-known URI with no-prefix code 0x00`() {
        val payload = wellKnownUriPayload(0x00, "jetfuelqcdemo://equipment?equipment_id=123")
        assertEquals(
            "jetfuelqcdemo://equipment?equipment_id=123",
            decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload),
        )
    }

    @Test
    fun `decodes well-known URI with http prefix 0x03`() {
        val payload = wellKnownUriPayload(0x03, "example.com/path")
        assertEquals("http://example.com/path", decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    @Test
    fun `decodes well-known URI with https prefix 0x04`() {
        val payload = wellKnownUriPayload(0x04, "example.com/secure")
        assertEquals("https://example.com/secure", decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    @Test
    fun `decodes well-known URI with https-www prefix 0x02`() {
        val payload = wellKnownUriPayload(0x02, "example.com")
        assertEquals("https://www.example.com", decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    @Test
    fun `returns null for well-known URI with empty payload`() {
        assertNull(decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, byteArrayOf()))
    }

    @Test
    fun `returns null for well-known URI with wrong type bytes`() {
        val wrongType = byteArrayOf('T'.code.toByte()) // RTD_TEXT, not RTD_URI
        val payload = wellKnownUriPayload(0x00, "something")
        assertNull(decodeUriPayload(TNF_WELL_KNOWN, wrongType, payload))
    }

    @Test
    fun `returns null for well-known URI with blank suffix and no prefix`() {
        val payload = wellKnownUriPayload(0x00, "   ")
        assertNull(decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    @Test
    fun `decodes well-known URI with mailto prefix 0x06`() {
        val payload = wellKnownUriPayload(0x06, "user@example.com")
        assertEquals("mailto:user@example.com", decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    @Test
    fun `unknown prefix code falls back to empty string prefix`() {
        val payload = wellKnownUriPayload(0xFF, "fallback-uri")
        assertEquals("fallback-uri", decodeUriPayload(TNF_WELL_KNOWN, RTD_URI_TYPE, payload))
    }

    // --- TNF_ABSOLUTE_URI ---

    @Test
    fun `decodes absolute URI from type bytes`() {
        val typeBytes = "https://absolute.example.com/path".toByteArray(Charsets.UTF_8)
        assertEquals(
            "https://absolute.example.com/path",
            decodeUriPayload(TNF_ABSOLUTE_URI, typeBytes, byteArrayOf()),
        )
    }

    @Test
    fun `returns null for absolute URI with blank type bytes`() {
        assertNull(decodeUriPayload(TNF_ABSOLUTE_URI, byteArrayOf(), byteArrayOf()))
    }

    // --- Unsupported TNFs ---

    @Test
    fun `returns null for TNF_EMPTY (0x00)`() {
        assertNull(decodeUriPayload(0x00, byteArrayOf(), byteArrayOf()))
    }

    @Test
    fun `returns null for TNF_MIME_MEDIA (0x02)`() {
        val typeBytes = "text/plain".toByteArray(Charsets.UTF_8)
        val payload = "hello".toByteArray(Charsets.UTF_8)
        assertNull(decodeUriPayload(0x02, typeBytes, payload))
    }
}
