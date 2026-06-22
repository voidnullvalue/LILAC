package com.lilac.nfcbatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EquipmentIdTest {

    @Test
    fun `extracts equipment_id from valid URI`() {
        assertEquals("36142", extractEquipmentId("jetfuelqcdemo://equipment?equipment_id=36142"))
    }

    @Test
    fun `returns null when equipment_id parameter is absent`() {
        assertNull(extractEquipmentId("jetfuelqcdemo://equipment?other_param=value"))
    }

    @Test
    fun `returns null for URI with no query string`() {
        assertNull(extractEquipmentId("jetfuelqcdemo://equipment"))
    }

    @Test
    fun `returns null for blank URI`() {
        assertNull(extractEquipmentId(""))
    }

    @Test
    fun `returns null for whitespace-only URI`() {
        assertNull(extractEquipmentId("   "))
    }

    @Test
    fun `returns null for malformed URI with spaces`() {
        assertNull(extractEquipmentId("not a valid uri"))
    }

    @Test
    fun `returns null when equipment_id value is blank`() {
        assertNull(extractEquipmentId("jetfuelqcdemo://equipment?equipment_id="))
    }

    @Test
    fun `extracts equipment_id from multi-parameter URI`() {
        assertEquals("36142", extractEquipmentId("jetfuelqcdemo://equipment?foo=bar&equipment_id=36142&baz=qux"))
    }

    @Test
    fun `does not alter original URI string`() {
        val uri = "jetfuelqcdemo://equipment?equipment_id=36142"
        extractEquipmentId(uri)
        assertEquals("jetfuelqcdemo://equipment?equipment_id=36142", uri)
    }

    @Test
    fun `BatchRow equipmentId matches extracted value`() {
        val row = BatchRow(rowNumber = 1, id = null, uri = "jetfuelqcdemo://equipment?equipment_id=36142")
        assertEquals("36142", row.equipmentId)
    }

    @Test
    fun `BatchRow equipmentId is null for URI without equipment_id`() {
        val row = BatchRow(rowNumber = 1, id = null, uri = "jetfuelqcdemo://equipment")
        assertNull(row.equipmentId)
    }

    @Test
    fun `BatchRow uri is unchanged after equipmentId access`() {
        val uri = "jetfuelqcdemo://equipment?equipment_id=36142"
        val row = BatchRow(rowNumber = 1, id = null, uri = uri)
        assertEquals("36142", row.equipmentId)
        assertEquals(uri, row.uri)
    }
}
