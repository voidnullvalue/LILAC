package com.lilac.nfcbatch

/**
 * Extracts the `equipment_id` query parameter from a URI string.
 * Uses java.net.URI for proper query parsing (not regex).
 * Equivalent to Uri.parse(uriString).getQueryParameter("equipment_id") on Android.
 *
 * Returns null if the parameter is absent, blank, or the URI is malformed.
 */
fun extractEquipmentId(uriString: String): String? {
    if (uriString.isBlank()) return null
    return try {
        java.net.URI(uriString).query
            ?.split("&")
            ?.firstOrNull { it.startsWith("equipment_id=") }
            ?.substringAfter("=")
            ?.ifBlank { null }
    } catch (_: Exception) {
        null
    }
}
