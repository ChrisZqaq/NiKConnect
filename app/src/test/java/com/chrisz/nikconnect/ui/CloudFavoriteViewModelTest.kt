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
        var books: List<CloudBook> = listOf(CloudBook("book-1", "默认收藏夹", 2)),
        var createBookResult: String = "book-new",
        var createError: CloudException? = null,
        var favoriteError: CloudException? = null,
        var favoriteResult: List<String> = emptyList(),
        var identityError: CloudException? = null,
    ) : CloudAccountApi {
        var lastFavoriteBookId: String? = null
        var lastFavoriteIds: List<String> = emptyList()
        var createdBookName: String? = null
        var identityCalls = 0

        override suspend fun fetchIdentity(): CloudIdentity {
            identityCalls++
            identityError?.let { throw it }
            return CloudIdentity(guid = identityGuid)
        }

        override suspend fun fetchMyRecipes(): CloudMyRecipes =
            CloudMyRecipes(books = books, recipeCount = books.sumOf { it.recipeCount })

        override suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>): List<String> {
            favoriteError?.let { throw it }
            lastFavoriteBookId = bookId
            lastFavoriteIds = recipeIds
            return favoriteResult
        }

        override suspend fun createBook(name: String): String {
            createError?.let { throw it }
            createdBookName = name
            return createBookResult
        }

        var librarySave: Triple<String, String, String>? = null

        override suspend fun saveToLibrary(name: String, resourceId: String, createdBy: Int): String {
            favoriteError?.let { throw it }
            librarySave = Triple(name, resourceId, createdBy.toString())
            return resourceId
        }

        // 收藏流程不碰槽位：这些方法只为满足接口
        override suspend fun listCameras(): List<CloudCamera> = emptyList()

        override suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot> =
            emptyList()

        override suspend fun fetchAssignable(): CloudAssignable = CloudAssignable(emptyList())

        override suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>): String = ""

        override suspend fun deleteSlot(cameraId: String, recipeId: String): String = ""

        override suspend fun addRecipes(cameraId: String, recipeIds: List<String>): String = ""

        override suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? = null
    }

    /** 记录每次创建 api 时传入的 guid，用来验证"权威 guid 是否被用上"。 */
    private class ApiRecorder(private val api: FakeApi) {
        val guids = mutableListOf<String>()
        fun factory(): (String, String) -> CloudAccountApi = { _, guid ->
            guids += guid
            api
        }
    }

    @Test
    fun `create book and favorite ends with notice and no phase`() = runTest(dispatcher) {
        val api = FakeApi(favoriteResult = listOf("recipe-1"))
        val model = CloudFavoriteViewModel("token", "01STALEGUID00000000000000", apiFactory = api.let { { _: String, _: String -> it } })

        model.createBookAndFavorite("recipe-1")
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.working)
        assertNull(state.phase)
        assertNull(state.error)
        assertEquals("已新建《NiKConnect》并收藏 1 个方案", state.notice)
        assertEquals("book-new", api.lastFavoriteBookId)
        assertEquals(listOf("recipe-1"), api.lastFavoriteIds)
    }

    @Test
    fun `create book failure keeps error with call id and detail`() = runTest(dispatcher) {
        val api = FakeApi(createError = CloudException("IF_IR_R_PB002 HTTP 422：", httpStatus = 422))
        val model = CloudFavoriteViewModel("token", "g", apiFactory = { _: String, _: String -> api })

        model.createBookAndFavorite("recipe-1")
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.working)
        assertNull(state.phase)
        assertNull(state.notice)
        assertTrue(state.error.orEmpty().startsWith("IF_IR_R_PB002"))
        assertTrue(state.error.orEmpty().contains("422"))
        assertTrue(state.detail.orEmpty().contains("IF_IR_R_PB002 HTTP 422"))
        assertNull(api.lastFavoriteBookId)
    }

    @Test
    fun `favorite into existing book failure reports PR001`() = runTest(dispatcher) {
        val api = FakeApi(favoriteError = CloudException("", errorCode = "E01_9999"))
        val model = CloudFavoriteViewModel("token", "g", apiFactory = { _: String, _: String -> api })
        val book = CloudBook("book-1", "默认收藏夹", 0)

        model.favoriteInto("recipe-9", book)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("IF_IR_R_PR001 返回 E01_9999", state.error)
        assertFalse(state.working)
    }

    @Test
    fun `authoritative guid from userinfo is used instead of captured guid`() = runTest(dispatcher) {
        val api = FakeApi()
        val recorder = ApiRecorder(api)
        val model = CloudFavoriteViewModel("token", "01STALEGUID00000000000000", apiFactory = recorder.factory())

        model.createBookAndFavorite("recipe-1")
        advanceUntilIdle()

        // 第一次构造 api 用的是登录时抓到的可疑 guid，ensureGuid 之后应换成 userinfo 的权威值
        assertEquals("01STALEGUID00000000000000", recorder.guids.first())
        assertTrue(recorder.guids.drop(1).all { it == "01REALGUID0000000000000000" })
        assertEquals(1, api.identityCalls)
    }

    @Test
    fun `load books populates list and clears loading`() = runTest(dispatcher) {
        val api = FakeApi(books = listOf(CloudBook("a", "书A", 3), CloudBook("b", "书B", 1)))
        val model = CloudFavoriteViewModel("token", "g", apiFactory = { _: String, _: String -> api })

        model.loadBooks()
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.loading)
        assertEquals(2, state.books.size)
        assertEquals("书A", state.books.first().label)
        assertEquals(4, state.recipeCount)
        assertNull(state.error)
    }

    @Test
    fun `identity failure falls back to captured guid since refresh handles expiry`() = runTest {
        val api = FakeApi(identityError = CloudException("OIDC_USERINFO HTTP 401：invalid_token", httpStatus = 401))
        val vm = CloudFavoriteViewModel("token", "guid", apiFactory = { _, _ -> api })
        vm.saveToLibrary("01RECIPE", "Mate Film")
        advanceUntilIdle()

        val state = vm.state.value
        // 账号信息取不到时不再直接失败：用登录时抓到的 guid 继续，401 由 withSession 的续期负责
        assertNull(state.error)
        assertEquals("01RECIPE", api.librarySave?.second)
    }

    @Test
    fun `saveToLibrary copies recipe into own library instead of a book`() = runTest {
        val fake = FakeApi()
        val vm = CloudFavoriteViewModel("token", "guid", apiFactory = { _, _ -> fake })
        vm.saveToLibrary("01RECIPE", "Mate Film by Choi matae")
        advanceUntilIdle()

        assertEquals(
            Triple("Mate Film by Choi matae", "01RECIPE", "2"),
            fake.librarySave,
        )
        val state = vm.state.value
        assertFalse(state.working)
        assertNull(state.error)
        assertTrue(state.notice.orEmpty().contains("收藏"))
    }
}
