package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.cloud.CloudRecipe
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.OwnedRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 槽位选择器合并列表：同一方案不许出现两行，占用状态要能反映当前排布。 */
class CloudSlotPickerModelTest {

    private fun slot(index: Int, recipeId: String? = null, name: String = "") =
        CloudSlot(index = index, recipeId = recipeId, name = name)

    @Test
    fun mergedList_dedupesByIdAndKeepsPoolFlag() {
        val pool = listOf(CloudRecipe(id = "L1", name = "Mate Film"))
        val library = listOf(
            OwnedRecipe(id = "L1", name = "Mate Film"),
            OwnedRecipe(id = "L2", name = "Mid-Spring"),
        )
        val entries = pickerEntries(pool, library, listOf(slot(1, "L1", "Mate Film")))

        assertEquals(listOf("L1", "L2"), entries.map { it.id })
        assertTrue(entries.first { it.id == "L1" }.inPool)
        assertFalse(entries.first { it.id == "L2" }.inPool)
        assertEquals(1, entries.first { it.id == "L1" }.slotIndex)
        assertEquals(null, entries.first { it.id == "L2" }.slotIndex)
    }

    @Test
    fun mergedList_ordersOccupiedFirst() {
        val library = listOf(
            OwnedRecipe("A", "Aaa"),
            OwnedRecipe("B", "Bbb"),
            OwnedRecipe("C", "Ccc"),
        )
        val entries = pickerEntries(
            pool = emptyList(),
            library = library,
            slots = listOf(slot(1), slot(2, "C", "Ccc"), slot(3, "B", "Bbb")),
        )
        assertEquals(listOf("C", "B", "A"), entries.map { it.id })
    }

    @Test
    fun labels_describeSlotState() {
        val atTarget = PickerEntry("X", "X", slotIndex = 2, inPool = true)
        val elsewhere = PickerEntry("Y", "Y", slotIndex = 5, inPool = true)
        val free = PickerEntry("Z", "Z", slotIndex = null, inPool = false)

        assertEquals("已在此位", pickerLabel(atTarget, targetPosition = 1))
        assertEquals("已在第 5 位", pickerLabel(elsewhere, targetPosition = 1))
        assertEquals("可添加", pickerLabel(free, targetPosition = 1))
    }
}
