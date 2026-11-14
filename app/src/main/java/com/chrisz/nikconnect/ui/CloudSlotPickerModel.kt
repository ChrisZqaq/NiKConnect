package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.cloud.CloudRecipe
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.OwnedRecipe

/**
 * 槽位选择器里的一行。
 *
 * 之前「账号可分配池」和「账号里的方案」是两个独立列表，同一个方案可能两边都出现，
 * 用户不知道点哪个、也不知道它是不是已经占了别的槽位。这里合成一份：
 * - `slotIndex` 非空表示当前排布里它在第几位（1 基）；
 * - `inPool` 表示它已在相机可分配池里（可以直接排进槽位，不必先 C003）；
 * - 两个来源各自保留原始对象，点击时才知道该走 C003 还是纯 PATCH。
 */
internal data class PickerEntry(
    val id: String,
    val name: String,
    val pictureControlName: String = "",
    val slotIndex: Int? = null,
    val inPool: Boolean = false,
    val libraryItem: OwnedRecipe? = null,
    val poolItem: CloudRecipe? = null,
)

/** 把池与库合成一份列表：按"当前占用位"排序，未占用的排在后面。 */
internal fun pickerEntries(
    pool: List<CloudRecipe>,
    library: List<OwnedRecipe>,
    slots: List<CloudSlot>,
): List<PickerEntry> {
    val occupancy = slots
        .mapNotNull { slot -> slot.recipeId?.takeIf { it.isNotBlank() }?.let { it to slot.index } }
        .toMap()

    val fromPool = pool.map { recipe ->
        PickerEntry(
            id = recipe.id,
            name = recipe.name,
            pictureControlName = recipe.pictureControlName,
            slotIndex = occupancy[recipe.id],
            inPool = true,
            poolItem = recipe,
        )
    }
    val poolIds = fromPool.map { it.id }.toSet()
    val fromLibrary = library
        .filter { it.id !in poolIds }
        .map { recipe ->
            PickerEntry(
                id = recipe.id,
                name = recipe.name,
                pictureControlName = recipe.pictureControlName,
                slotIndex = occupancy[recipe.id],
                inPool = false,
                libraryItem = recipe,
            )
        }

    return (fromPool + fromLibrary).sortedWith(
        compareBy<PickerEntry> { it.slotIndex ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() },
    )
}

/** 行右侧的状态文案。 */
internal fun pickerLabel(entry: PickerEntry, targetPosition: Int): String = when {
    entry.slotIndex == targetPosition + 1 -> "已在此位"
    entry.slotIndex != null -> "已在第 ${entry.slotIndex} 位"
    else -> "可添加"
}
