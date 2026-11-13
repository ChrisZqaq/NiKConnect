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
}
