package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAssignable
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
import com.chrisz.nikconnect.cloud.CloudBook
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudIdentity
import com.chrisz.nikconnect.cloud.CloudMyRecipes
import com.chrisz.nikconnect.cloud.OwnedRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收藏流程的状态机测试（纯 JVM，不连网、不真机）。
 *
 * 这些用例锁的是真机上踩过的坑：请求失败必须留下 `error` 与 `detail`，不能"转一圈就没了"；
 * 建夹/收藏前必须先向 OIDC 取权威 guid，不能用登录时嗅探到的可疑值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudFavoriteViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(
        var identityGuid: String = "01REALGUID0000000000000000",
        var identityError: CloudException? = null,
        var recipes: List<OwnedRecipe> = emptyList(),
        var expireFirstSave: Boolean = false,
        var refreshResult: Pair<String, String?>? = null,
    ) : CloudAccountApi {
        var librarySave: Triple<String, String, String>? = null
        var removedIds: List<String>? = null
        var lastToken: String = ""
        var refreshCalls = 0
        private var saved = false

        override suspend fun fetchIdentity(): CloudIdentity {
            identityError?.let { throw it }
            return CloudIdentity(guid = identityGuid)
        }

        override suspend fun fetchMyRecipes(): CloudMyRecipes =
            CloudMyRecipes(books = emptyList(), recipeCount = recipes.size, recipes = recipes)

        override suspend fun saveToLibrary(name: String, resourceId: String, createdBy: Int): String {
            if (expireFirstSave && !saved) {
                saved = true
                throw CloudException("IF_IR_R_LR002 HTTP 401", httpStatus = 401)
            }
            librarySave = Triple(name, resourceId, createdBy.toString())
            // 真实 LR002 会把方案写进账号库；夹具必须同样反映，否则"操作前后差集"永远为空。
            recipes = recipes + OwnedRecipe(id = "01NEWLOCAL", name = name)
            return "01NEWLOCAL"
        }

        override suspend fun removeFromLibrary(localIds: List<String>): String {
            removedIds = localIds
            recipes = recipes.filterNot { it.id in localIds }
            return ""
        }

        override suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? {
            refreshCalls++
            return refreshResult
        }

        var cameras: List<CloudCamera> = emptyList()
        var layouts: Map<String, List<String?>> = emptyMap()

        override suspend fun listCameras(): List<CloudCamera> = cameras
        override suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot> = emptyList()
        override suspend fun fetchAssignable(): CloudAssignable =
            CloudAssignable(recipes = emptyList(), layouts = layouts)
        override suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>): String = ""
        override suspend fun deleteSlot(cameraId: String, recipeId: String): String = ""
        override suspend fun addRecipes(cameraId: String, recipeIds: List<String>): String = ""
        override suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>): List<String> = emptyList()
        override suspend fun createBook(name: String): String = "book-new"
    }

    @Test
    fun `toggle marks collected when recipe is in my library`() = runTest(dispatcher) {
        val fake = FakeApi()
        val vm = CloudFavoriteViewModel("t", "g", apiFactory = { _, _ -> fake })
        vm.loadCollection("Mate Film")
        advanceUntilIdle()
        assertNull(vm.state.value.collected)

        vm.toggleCollection("01RECIPE", "Mate Film")
        advanceUntilIdle()

        assertEquals(Triple("Mate Film", "01RECIPE", "2"), fake.librarySave)
        assertTrue(vm.state.value.notice.orEmpty().contains("收藏"))
    }

    @Test
    fun `loadCollection finds existing recipe by name`() = runTest(dispatcher) {
        val fake = FakeApi()
        fake.recipes = listOf(OwnedRecipe(id = "01LOCAL", name = "Mate Film"))
        val vm = CloudFavoriteViewModel("t", "g", apiFactory = { _, _ -> fake })
        vm.loadCollection("mate film")
        advanceUntilIdle()

        assertEquals("01LOCAL", vm.state.value.collected?.id)
    }

    @Test
    fun `removeFromCollection deletes by local id`() = runTest(dispatcher) {
        val fake = FakeApi()
        fake.recipes = listOf(OwnedRecipe(id = "01LOCAL", name = "Mate Film"))
        val vm = CloudFavoriteViewModel("t", "g", apiFactory = { _, _ -> fake })
        vm.loadCollection("Mate Film")
        advanceUntilIdle()

        vm.removeFromCollection("01LOCAL")
        advanceUntilIdle()

        assertEquals(listOf("01LOCAL"), fake.removedIds)
        assertNull(vm.state.value.collected)
        assertTrue(vm.state.value.notice.orEmpty().contains("取消"))
    }

    @Test
    fun `loadCollection reports which camera slot the recipe occupies`() = runTest(dispatcher) {
        // 真机实测：取消收藏会把槽位一起清掉，所以详情页必须先说明"它正占着哪一位"
        val fake = FakeApi()
        fake.recipes = listOf(OwnedRecipe(id = "01LOCAL", name = "Mid-Spring"))
        fake.cameras = listOf(CloudCamera(id = "CAM1", name = "Zf", model = "Zf"))
        fake.layouts = mapOf("CAM1" to listOf("01OTHER", "01LOCAL", null))
        val vm = CloudFavoriteViewModel("t", "g", apiFactory = { _, _ -> fake })
        vm.loadCollection("Mid-Spring")
        advanceUntilIdle()

        assertEquals(
            Placements.Known(listOf(Placement(cameraName = "Zf", slot = 2))),
            vm.state.value.placements,
        )
    }

    @Test
    fun `removeFromCollection says which slots were cleared`() = runTest(dispatcher) {
        val fake = FakeApi()
        fake.recipes = listOf(OwnedRecipe(id = "01LOCAL", name = "Mid-Spring"))
        fake.cameras = listOf(CloudCamera(id = "CAM1", name = "Zf", model = "Zf"))
        fake.layouts = mapOf("CAM1" to listOf("01OTHER", "01LOCAL", null))
        val vm = CloudFavoriteViewModel("t", "g", apiFactory = { _, _ -> fake })
        vm.loadCollection("Mid-Spring")
        advanceUntilIdle()

        vm.removeFromCollection("01LOCAL")
        advanceUntilIdle()

        val notice = vm.state.value.notice.orEmpty()
        assertTrue("notice was: $notice", notice.contains("Zf 第 2 位"))
        assertTrue(vm.state.value.placements is Placements.Unknown)
    }

    @Test
    fun `expired session refreshes once and retries the save`() = runTest(dispatcher) {
        val fake = FakeApi(expireFirstSave = true, refreshResult = "new-token" to "new-refresh")
        val vm = CloudFavoriteViewModel("t", "g", refreshToken = "r", apiFactory = { tk, _ -> fake.also { it.lastToken = tk } })
        vm.toggleCollection("01RECIPE", "Mate Film")
        advanceUntilIdle()

        assertEquals(1, fake.refreshCalls)
        assertEquals("new-token", fake.lastToken)
        assertEquals("01RECIPE", fake.librarySave?.second)
    }
}
