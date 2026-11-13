package com.chrisz.nikconnect.cloud

/**
 * 收藏流程需要的那几个账号接口。
 *
 * 抽成接口只有一个目的：让 [com.chrisz.nikconnect.ui.CloudFavoriteViewModel] 能在纯 JVM 测试里
 * 被驱动（假实现直接返回 success / 抛 CloudException），不必连网、不必真机。
 */
internal interface CloudAccountApi {
    suspend fun fetchIdentity(): CloudIdentity

    suspend fun fetchMyRecipes(): CloudMyRecipes

    suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>): List<String>

    suspend fun createBook(name: String): String

    // ---- 相机与槽位（账号页用；抽到接口是为了让续期/重试逻辑能在 JVM 测试里被驱动） ----

    suspend fun listCameras(): List<CloudCamera>

    suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot>

    suspend fun fetchAssignable(): CloudAssignable

    suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>): String

    suspend fun deleteSlot(cameraId: String, recipeId: String): String

    /** 把账号里的方案加进「相机可分配池」（IF_IR_R_C003）。 */
    suspend fun addRecipes(cameraId: String, recipeIds: List<String>): String

    /** 用 refresh_token 换新 access_token；换不到返回 null。 */
    suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>?
}
