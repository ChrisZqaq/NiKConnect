package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAssignable
import com.chrisz.nikconnect.cloud.CloudBook
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudIdentity
import com.chrisz.nikconnect.cloud.CloudMyRecipes
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
import com.chrisz.nikconnect.cloud.OwnedRecipe
import com.chrisz.nikconnect.cloud.padSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「加到相机」四步链路的契约测试：LR002 → H05 取本地 id → C003 → H13 取排布 → C004。
 * 重点是 PATCH 必须**带上原有的其它槽位**，否则会把别人的位置覆盖掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudAddToCameraViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(
        var collected: MutableList<OwnedRecipe> = mutableListOf(),
        var layout: List<String?> = listOf(null, "KEEP-2", null, null, null, null, null, null, null),
    ) : CloudAccountApi {
        val calls = mutableListOf<String>()
        var addedRecipes: List<String> = emptyList()
        var patched: List<CloudSlotAssignment> = emptyList()
        val deleted = mutableListOf<String>()

        override suspend fun fetchIdentity(): CloudIdentity {
            calls += "identity"
            return CloudIdentity(guid = "0000000000062160")
        }

        override suspend fun fetchMyRecipes(): CloudMyRecipes {
            calls += "h05"
            return CloudMyRecipes(
                books = listOf(CloudBook("B1", "未归类", collected.size)),
                recipeCount = collected.size,
                recipes = collected.toList(),
            )
        }

        override suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>) = recipeIds

        override suspend fun createBook(name: String): String = "B1"

        override suspend fun removeFromLibrary(localIds: List<String>): String {
            collected.removeAll { it.id in localIds }
            return "200"
        }

        override suspend fun saveToLibrary(name: String, resourceId: String, createdBy: Int): String {
            calls += "lr002"
            collected.add(OwnedRecipe(id = "LOCAL-$resourceId", name = name))
            return resourceId
        }

        override suspend fun listCameras(): List<CloudCamera> {
            calls += "c001"
            return listOf(CloudCamera(id = "CAM1", name = "Zf", model = "", serial = ""))
        }

        override suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot> {
            calls += "c002"
            return padSlots(emptyList())
        }

        override suspend fun fetchAssignable(): CloudAssignable {
            calls += "h13"
            return CloudAssignable(recipes = emptyList(), layouts = mapOf("CAM1" to layout))
        }

        override suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>): String {
            calls += "c004"
            patched = assignments
            return "200"
        }

        override suspend fun deleteSlot(cameraId: String, recipeId: String): String {
            calls += "c005"
            deleted += recipeId
            layout = layout.map { if (it == recipeId) null else it }
            return "200"
        }

        override suspend fun addRecipes(cameraId: String, recipeIds: List<String>): String {
            calls += "c003"
            addedRecipes = recipeIds
            // 真机上 C003 只是把方案加进"可分配池"，槽位排布不会自己变，这里保持 layout 不动。
            return "201"
        }

        override suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? = null
    }

    @Test
    fun addToSlot_runsFullChainAndKeepsOtherSlots() = runTest(dispatcher) {
        val api = FakeApi()
        val model = CloudAddToCameraViewModel(
            token = "t",
            capturedGuid = "0000000000062160",
            clientFactory = { _, _ -> api },
        )

        model.addToSlot(
            camera = CloudCamera(id = "CAM1", name = "Zf", model = "", serial = ""),
            position = 0,
            recipeId = "01M3P67BB9NR1QMJHMQ870XW52",
            recipeName = "Mate Film by Choi matae",
        )
        advanceUntilIdle()

        assertEquals(listOf("lr002", "h05", "c003", "h13", "c004"), api.calls.take(5))
        assertEquals(listOf("LOCAL-01M3P67BB9NR1QMJHMQ870XW52"), api.addedRecipes)
        // 第 1 位换成新方案，第 2 位原有的 KEEP-2 必须保留
        assertEquals(
            listOf(
                CloudSlotAssignment(1, "LOCAL-01M3P67BB9NR1QMJHMQ870XW52"),
                CloudSlotAssignment(2, "KEEP-2"),
            ),
            api.patched,
        )
        assertTrue(model.state.value.notice.orEmpty().contains("第 1 位"))
        assertNull(model.state.value.error)
    }

    @Test
    fun addToSlot_skipsLibraryWhenAlreadyCollected() = runTest(dispatcher) {
        val api = FakeApi()
        val model = CloudAddToCameraViewModel(
            token = "t",
            capturedGuid = "0000000000062160",
            clientFactory = { _, _ -> api },
        )

        model.addToSlot(
            camera = CloudCamera(id = "CAM1", name = "Zf", model = "", serial = ""),
            position = 4,
            recipeId = "PUB-1",
            recipeName = "Mid-Spring by Yinukhi",
            alreadyCollectedId = "LOCAL-9",
        )
        advanceUntilIdle()

        assertTrue(api.calls.none { it == "lr002" })
        assertTrue(api.calls.contains("c004"))
        assertEquals(listOf("LOCAL-9"), api.addedRecipes)
        // 目标位 5，其它两位（1 新加、2 原有）都要在请求里
        assertEquals(
            listOf(
                CloudSlotAssignment(2, "KEEP-2"),
                CloudSlotAssignment(5, "LOCAL-9"),
            ),
            api.patched,
        )
    }

    @Test
    fun slotOf_reportsOneBasedPosition() {
        assertEquals(2, slotOf(listOf(null, "RID", null), "RID"))
        assertNull(slotOf(listOf(null, null), "RID"))
        assertNull(slotOf(null, "RID"))
    }

    @Test
    fun collectedIdOf_matchesNameIgnoringCase() {
        val recipes = listOf(OwnedRecipe("L1", "Mate Film by Choi matae"))
        assertEquals("L1", collectedIdOf(recipes, "mate film by choi matae"))
        assertNull(collectedIdOf(recipes, "missing"))
    }

    @Test
    fun addToSlot_deletesTheOccupantBeforePatching() = runTest(dispatcher) {
        // 真机 2026-11-14：直接把已占用的槽位换成另一个方案，PATCH 会 400 ——
        // 服务端把这种改动当成"移除旧方案"，必须先用 C005 删掉它。
        val api = FakeApi()
        val model = CloudAddToCameraViewModel(
            token = "t",
            capturedGuid = "0000000000062160",
            clientFactory = { _, _ -> api },
        )

        model.addToSlot(
            camera = CloudCamera(id = "CAM1", name = "Zf", model = "", serial = ""),
            position = 1,
            recipeId = "PUB-2",
            recipeName = "Blue Hour by Luna",
            alreadyCollectedId = "LOCAL-9",
        )
        advanceUntilIdle()

        assertEquals(listOf("KEEP-2"), api.deleted)
        assertEquals(listOf(CloudSlotAssignment(2, "LOCAL-9")), api.patched)
        assertNull(model.state.value.error)
    }

    @Test
    fun addToSlot_refusesToPatchWhenLayoutIsUnknown() = runTest(dispatcher) {
        // H13 没给这台相机的排布 → 现场状态未知，绝不能拿"空布局"去提交
        val api = object : CloudAccountApi by FakeApi() {
            override suspend fun fetchAssignable(): CloudAssignable =
                CloudAssignable(recipes = emptyList(), layouts = emptyMap())
        }
        val model = CloudAddToCameraViewModel(
            token = "t",
            capturedGuid = "0000000000062160",
            clientFactory = { _, _ -> api },
        )

        model.addToSlot(
            camera = CloudCamera(id = "CAM1", name = "Zf", model = "", serial = ""),
            position = 0,
            recipeId = "01M3P67BB9NR1QMJHMQ870XW52",
            recipeName = "Mate Film by Choi matae",
        )
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(null, state.working)
        assertTrue("error was: ${state.error}", state.error.orEmpty().contains("没读到相机当前的槽位排布"))
    }
}
