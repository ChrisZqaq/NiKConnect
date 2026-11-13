package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 云创账号侧纯 JVM 测试：只测响应解析（网络与 token 不在此层）。
 * 官方响应形状尚未在真机核验，因此这里同时覆盖「多种包裹层级」和「形状不认识」两种情况。
 */
class CloudAccountClientTest {

    private fun client() = CloudAccountClient(accessToken = "test-token", guid = "01TESTGUID0000000000000000")

    // ---- 相机列表 ----

    @Test
    fun `camera list under body and cameraList`() {
        val raw = """
            {"statusCode":200,"body":{"cameraList":[
              {"id":"CAM1","name":"我的 Zf","model":"Zf"},
              {"id":"CAM2","cameraName":"备机","modelName":"Z6III"}
            ]}}
        """.trimIndent()
        val cameras = client().parseCameras(raw)
        assertEquals(2, cameras.size)
        assertEquals("CAM1", cameras[0].id)
        assertEquals("我的 Zf", cameras[0].name)
        assertEquals("Zf", cameras[0].model)
        assertEquals("备机", cameras[1].name)
        assertEquals("Z6III", cameras[1].model)
    }

    @Test
    fun `camera list tolerates other keys and bare array`() {
        val nested = """{"cameras":[{"cameraId":"C9","nickname":"夜间机","productName":"Zf"}]}"""
        assertEquals("C9", client().parseCameras(nested).single().id)

        val bare = """[{"id":"C1","name":"裸数组"}]"""
        assertEquals("裸数组", client().parseCameras(bare).single().name)
    }

    @Test
    fun `empty account yields empty list`() {
        assertTrue(client().parseCameras("""{"statusCode":200,"body":{}}""").isEmpty())
        assertTrue(client().parseCameras("""{"statusCode":200,"body":[]}""").isEmpty())
        assertTrue(client().parseCameras("""{"statusCode":200,"body":{"cameraList":[]}}""").isEmpty())
    }

    @Test
    fun `unknown shape is an error not an empty account`() {
        // 回归：以前把「结构不认识」当成「账号下没有相机」，用户看到的是错误的空态
        val e = assertThrows(CloudException::class.java) {
            client().parseCameras("""{"statusCode":200,"body":{"note":"nothing here"}}""")
        }
        assertTrue(e.message.orEmpty().contains("结构无法识别"))
    }

    // ---- 槽位 ----

    @Test
    fun `slots keep array order and occupancy`() {
        val raw = """
            {"statusCode":200,"body":{"recipes":[
              {"id":"R1","exist":true,"pictureControlName":"NegativeFilm Red&Cyan 01b","name":"红青负片","modifyDate":"2026-11-01"},
              {"exist":false},
              {"id":"R3","exist":"true","pictureControlName":"MOSS","name":"MOSS"}
            ]}}
        """.trimIndent()
        val slots = client().parseSlots(raw)
        assertEquals(3, slots.size)
        assertEquals(1, slots[0].index)
        assertEquals("R1", slots[0].recipeId)
        assertTrue(slots[0].occupied)
        assertEquals("红青负片", slots[0].name)
        assertEquals(2, slots[1].index)
        assertNull(slots[1].recipeId)
        assertTrue(!slots[1].occupied)
        assertEquals("MOSS", slots[2].pictureControlName)
    }

    @Test
    fun `slots honour declared slot number`() {
        val raw = """{"body":{"slot_list":[],"cameraSlots":[{"slot":3,"id":"RX","name":"第三槽","pictureControlName":"X"}]}}"""
        val slots = client().parseSlots(raw)
        assertEquals(3, slots.single().index)
    }

    // ---- 可分配方案 ----

    @Test
    fun `assignable recipes parse cover and control name`() {
        val raw = """
            {"body":{"recipe":[
              {"id":"A1","name":"Winter Hues","picture_controll_name":"Winter Hues","public_date":"2026-10-01","img":{"url":"https://front/x.JPG"}},
              {"id":"A2","name":"Quiet","pictureControlName":"QUIET"}
            ]}}
        """.trimIndent()
        val list = client().parseAssignable(raw)
        assertEquals(2, list.size)
        assertEquals("Winter Hues", list[0].pictureControlName)
        assertEquals("https://front/x.JPG", list[0].coverUrl)
        assertEquals("QUIET", list[1].pictureControlName)
    }

    // ---- 错误与坏数据 ----

    @Test
    fun `rate limit code surfaces as exception with code`() {
        val raw = """{"statusCode":200,"body":{"error_code":"E01_0701"}}"""
        try {
            client().parseCameras(raw)
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertEquals("E01_0701", e.errorCode)
        }
    }

    @Test
    fun `malformed json surfaces as exception`() {
        try {
            client().parseSlots("""{"body": {"recipes": [""")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("不是合法 JSON"))
        }
    }

    // ---- 账号身份（Keycloak userinfo） ----

    @Test
    fun `identity takes guid claim`() {
        val raw = """{"sub":"a0b1","guid":"01HXDNNTHY1AZ6RVMMXSXY3BTK","languageCode":"zh-cn","countryCode":"CN"}"""
        val identity = client().parseIdentity(raw)
        assertEquals("01HXDNNTHY1AZ6RVMMXSXY3BTK", identity.guid)
        assertEquals("zh-cn", identity.language)
        assertEquals("CN", identity.country)
    }

    @Test
    fun `identity falls back to guid shaped sub`() {
        val raw = """{"sub":"01JD7Z5Q9W3T6K2M4N8P0R5SYV"}"""
        assertEquals("01JD7Z5Q9W3T6K2M4N8P0R5SYV", client().parseIdentity(raw).guid)
    }

    @Test
    fun `identity without guid is an error`() {
        // 回归：以前允许「猜」出账号 ID，于是把方案 ID/bookId 当账号 ID 去请求，服务端回 422
        val e = assertThrows(CloudException::class.java) {
            client().parseIdentity("""{"sub":"not-a-guid","preferred_username":"abc"}""")
        }
        assertTrue(e.message.orEmpty().contains("没有账号 ID"))
    }

    @Test
    fun `identity rejects non object`() {
        assertThrows(CloudException::class.java) { client().parseIdentity("""[{"guid":"01JD7Z5Q9W3T6K2M4N8P0R5SYV"}]""") }
    }

    // ---- 真机实测形状（2026-11-13） ----

    @Test
    fun `real camera payload uses deviceId`() {
        // C001 真身：{"cameras":[{"deviceId":"01JTC8JSRZAW1B2FZZYGF8GYBW","name":"Zf","serialNo":"8032632…"}]}
        val raw = """{"cameras":[{"deviceId":"01JTC8JSRZAW1B2FZZYGF8GYBW","name":"Zf","serialNo":"8032632","custProductMngNo":"1","productUrl":"https://imagingcloud.nikon.com.cn/img/x.png"}]}"""
        val camera = client().parseCameras(raw).single()
        assertEquals("01JTC8JSRZAW1B2FZZYGF8GYBW", camera.id)
        assertEquals("Zf", camera.name)
        assertEquals("8032632", camera.serial)
    }

    // ---- 排布写回（IF_IR_R_C004） ----

    @Test
    fun `slot body sends slotNo and recipeId sorted`() {
        val body = client().buildSlotBody(
            listOf(
                CloudSlotAssignment(slotNo = 3, recipeId = "01JAAA"),
                CloudSlotAssignment(slotNo = 1, recipeId = "01JCCC"),
            ),
        )
        assertEquals(
            """{"recipes":[{"slotNo":1,"recipeId":"01JCCC"},{"slotNo":3,"recipeId":"01JAAA"}]}""",
            body,
        )
    }

    @Test
    fun `slot body skips blank recipe ids`() {
        val body = client().buildSlotBody(
            listOf(
                CloudSlotAssignment(slotNo = 1, recipeId = ""),
                CloudSlotAssignment(slotNo = 2, recipeId = "01JBBB"),
            ),
        )
        assertEquals("""{"recipes":[{"slotNo":2,"recipeId":"01JBBB"}]}""", body)
    }

    @Test
    fun `slot body with nothing occupied is an empty list`() {
        assertEquals("""{"recipes":[]}""", client().buildSlotBody(emptyList()))
    }

    // ---- 写回计划（planSlotWrite） ----

    @Test
    fun `unchanged layout plans nothing`() {
        val plan = planSlotWrite(listOf("A", "B"), listOf("A", "B"))
        assertTrue(plan.noop)
        assertNull(plan.assignments)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `reorder plans a full patch with one-based slot numbers`() {
        val plan = planSlotWrite(listOf("A", "B"), listOf("B", "A"))
        assertFalse(plan.noop)
        assertEquals(
            listOf(
                CloudSlotAssignment(slotNo = 1, recipeId = "B"),
                CloudSlotAssignment(slotNo = 2, recipeId = "A"),
            ),
            plan.assignments,
        )
        assertEquals("IF_IR_R_C004", plan.callId)
    }

    /** 真机踩过：全清空时发 `{"recipes":[]}` 被服务端回 422，Web 端根本不会发这个请求。 */
    @Test
    fun `clearing every slot plans deletes instead of an empty patch`() {
        val plan = planSlotWrite(listOf("A"), emptyList())
        assertNull(plan.assignments)
        assertEquals(listOf("A"), plan.deletes)
        assertEquals("IF_IR_R_C005", plan.callId)
    }

    @Test
    fun `adding into an empty slot plans a patch`() {
        val plan = planSlotWrite(listOf("A"), listOf("A", "C"))
        assertEquals(
            listOf(
                CloudSlotAssignment(slotNo = 1, recipeId = "A"),
                CloudSlotAssignment(slotNo = 2, recipeId = "C"),
            ),
            plan.assignments,
        )
    }
}
