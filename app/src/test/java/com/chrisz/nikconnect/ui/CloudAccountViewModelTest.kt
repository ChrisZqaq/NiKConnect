package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAssignable
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudIdentity
import com.chrisz.nikconnect.cloud.CloudMyRecipes
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
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
 * 账号页的续期逻辑测试（纯 JVM）。
 *
 * 锁的是 2026-11-14 真机发现：持久化的 access_token 会过期（`OIDC_USERINFO -> 401 invalid_token`）。
 * 期望行为是「先静默续期一次，续不上才请用户重登」，而不是直接把过期当成账号 ID 错误。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(
        var expireFirstIdentityCall: Boolean = false,
        var refreshResult: Pair<String, String?>? = null,
        var cameras: List<CloudCamera> = listOf(CloudCamera(id = "CAM1", name = "Zf", model = "Zf")),
    ) : CloudAccountApi {
        var identityCalls = 0
        var refreshCalls = 0
        var lastRefreshToken: String? = null
        var lastCameraToken: String? = null

        override suspend fun fetchIdentity(): CloudIdentity {
            identityCalls++
            if (expireFirstIdentityCall && identityCalls == 1) {
                throw CloudException("OIDC_USERINFO HTTP 401：invalid_token", httpStatus = 401)
            }
            return CloudIdentity(guid = "0000000000062160")
        }

        override suspend fun listCameras(): List<CloudCamera> {
            lastCameraToken = currentToken
            return cameras
        }

        override suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot> =
            emptyList()

        override suspend fun fetchAssignable(): CloudAssignable = CloudAssignable(emptyList())

        override suspend fun fetchMyRecipes(): CloudMyRecipes = CloudMyRecipes()

        override suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>): List<String> =
            emptyList()

        override suspend fun createBook(name: String): String = "book-1"

        override suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>): String = ""

        override suspend fun deleteSlot(cameraId: String, recipeId: String): String = ""

        override suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? {
            refreshCalls++
            lastRefreshToken = refreshToken
            return refreshResult
        }

        /** 工厂每次建客户端会带着当前 token 进来，记下来便于断言"续期后用的是新 token"。 */
        var currentToken: String = ""
    }

    private fun model(
        api: FakeApi,
        refreshToken: String?,
        onRefreshed: (CloudAuthState) -> Unit = {},
    ): CloudAccountViewModel = CloudAccountViewModel(
        token = "old-token",
        guid = null,
        refreshToken = refreshToken,
        onTokenRefreshed = onRefreshed,
        clientFactory = { token, _ ->
            api.currentToken = token
            api
        },
    )

    @Test
    fun `expired token is refreshed silently and cameras load`() = runTest(dispatcher) {
        val api = FakeApi(
            expireFirstIdentityCall = true,
            refreshResult = "new-token" to "new-refresh",
        )
        var refreshed: CloudAuthState? = null
        val model = model(api, refreshToken = "rt-1") { refreshed = it }
        advanceUntilIdle()

        assertEquals(1, api.refreshCalls)
        assertEquals("rt-1", api.lastRefreshToken)
        assertEquals(1, model.state.value.cameras.size)
        assertEquals("0000000000062160", model.state.value.guid)
        assertFalse(model.state.value.sessionExpired)
        assertNull(model.state.value.cameraError)
        assertEquals("new-token", refreshed?.token)
        assertEquals("new-refresh", refreshed?.refreshToken)
        assertEquals("new-token", api.lastCameraToken)
    }

    @Test
    fun `failed refresh falls back to asking the user to log in again`() = runTest(dispatcher) {
        val api = FakeApi(expireFirstIdentityCall = true, refreshResult = null)
        val model = model(api, refreshToken = "rt-dead")
        advanceUntilIdle()

        assertEquals(1, api.refreshCalls)
        assertTrue(model.state.value.sessionExpired)
        assertTrue(model.state.value.cameraError.orEmpty().contains("重新登录"))
        assertFalse(model.state.value.loadingCameras)
    }

    @Test
    fun `without refresh token we do not even try`() = runTest(dispatcher) {
        val api = FakeApi(expireFirstIdentityCall = true, refreshResult = "whatever" to null)
        val model = model(api, refreshToken = null)
        advanceUntilIdle()

        assertEquals(0, api.refreshCalls)
        assertTrue(model.state.value.sessionExpired)
    }
}
