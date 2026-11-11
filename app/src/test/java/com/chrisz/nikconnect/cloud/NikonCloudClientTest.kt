package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NikonCloudClient 纯 JVM 测试：只测 parseRecipeList 形状适配（网络调用不在此层测）。
 */
class NikonCloudClientTest {

    private fun client() = NikonCloudClient(accessToken = "test-token", guid = "test-guid")

    @Test
    fun `parses flat recipes wrapper`() {
        val raw = """
            {"recipes":[
              {"id":"r1","name":"Mate Film","creator":"Choi matae","thumbnailUrl":"https://x/t.jpg"},
              {"id":"r2","recipeName":"MOSS","creatorName":"Nagisa"}
            ],"total":144}
        """.trimIndent()
        val result = client().parseRecipeList(raw)
        assertEquals(2, result.recipes.size)
        assertEquals("r1", result.recipes[0].id)
        assertEquals("Mate Film", result.recipes[0].name)
        assertEquals("Choi matae", result.recipes[0].creator)
        assertEquals("MOSS", result.recipes[1].name)
        assertEquals(144, result.totalCount)
    }

    @Test
    fun `parses data nested wrapper`() {
        val raw = """{"data":{"recipes":[{"recipeId":"r9","title":"Bluegrain"}]},"totalCount":1}"""
        val result = client().parseRecipeList(raw)
        assertEquals("r9", result.recipes[0].id)
        assertEquals("Bluegrain", result.recipes[0].name)
        assertEquals(1, result.totalCount)
    }

    @Test
    fun `throws CloudException on unknown shape`() {
        try {
            client().parseRecipeList("""{"unexpected":"shape"}""")
            fail("expected CloudException")
        } catch (e: NikonCloudClient.CloudException) {
            assertTrue(e.message!!.contains("未识别"))
        }
    }

    @Test
    fun `throws CloudException on invalid json`() {
        try {
            client().parseRecipeList("<html>not json</html>")
            fail("expected CloudException")
        } catch (e: NikonCloudClient.CloudException) {
            assertTrue(e.message!!.contains("不是合法 JSON"))
        }
    }

    @Test
    fun `skips entries without id`() {
        val raw = """{"recipes":[{"name":"no-id"},{"id":"ok","name":"HasId"}]}"""
        val result = client().parseRecipeList(raw)
        assertEquals(1, result.recipes.size)
        assertEquals("ok", result.recipes[0].id)
    }
}
