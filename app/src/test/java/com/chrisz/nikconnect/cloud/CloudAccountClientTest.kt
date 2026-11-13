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

    /** 把方案 ID 列表铺成"从第 1 位连续占用"的槽位，等价于旧测试里的 List<String> 入参。 */
    private fun slotsOf(vararg ids: String) =
        ids.mapIndexed { i, id -> CloudSlot(index = i + 1, recipeId = id, name = id, pictureControlName = "pc") }

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
    fun `empty assignable pool is empty even though cameras are present`() {
        val client = CloudAccountClient(accessToken = "t", guid = "0000000000062160")
        // 真机原始响应：删空槽位后 recipe 为空，但 camera[] 还在（旧逻辑会把相机当方案）
        val raw = """{"authority":0,"recipe":[],"camera":[{"id":"1","deviceId":"01JTC8JS","name":"Zf","recipeIds":[null]}]}"""
        assertTrue(client.parseAssignable(raw).isEmpty())
    }

    @Test
    fun `empty slot list with other fields counts as empty not unrecognised`() {
        val client = CloudAccountClient(accessToken = "t", guid = "0000000000062160")
        // 真机原始响应：槽位删空后服务端就是这么回的，旧逻辑会把它判成「结构无法识别」
        val raw = """{"status":1,"localRecipes":[],"date":"2026-11-13T14:31:38+08:00"}"""
        assertTrue(client.parseSlots(raw).isEmpty())
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
        val plan = planSlotWrite(slotsOf("A", "B"), slotsOf("A", "B"))
        assertTrue(plan.noop)
        assertNull(plan.assignments)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `reorder plans a full patch with one-based slot numbers`() {
        val plan = planSlotWrite(slotsOf("A", "B"), slotsOf("B", "A"))
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
        val plan = planSlotWrite(slotsOf("A"), emptyList())
        assertNull(plan.assignments)
        assertEquals(listOf("A"), plan.deletes)
        assertEquals("IF_IR_R_C005", plan.callId)
    }

    @Test
    fun `adding into an empty slot plans a patch`() {
        val plan = planSlotWrite(slotsOf("A"), slotsOf("A", "C"))
        assertEquals(
            listOf(
                CloudSlotAssignment(slotNo = 1, recipeId = "A"),
                CloudSlotAssignment(slotNo = 2, recipeId = "C"),
            ),
            plan.assignments,
        )
    }

    // ---- 槽位补齐（真机：C002 只返回已占用项） ----

    @Test
    fun `padSlots fills an empty nine slot grid`() {
        val slots = padSlots(
            listOf(
                CloudSlot(index = 1, recipeId = "A", name = "A", pictureControlName = "pc", modifiedAt = ""),
                CloudSlot(index = 2, recipeId = "B", name = "B", pictureControlName = "pc", modifiedAt = ""),
            ),
        )
        assertEquals(9, slots.size)
        assertEquals((1..9).toList(), slots.map { it.index })
        assertTrue(slots[0].occupied)
        assertTrue(slots[1].occupied)
        assertFalse(slots[2].occupied)
    }

    @Test
    fun `padSlots keeps gaps and does not shift indexes`() {
        val slots = padSlots(listOf(CloudSlot(index = 5, recipeId = "E", name = "E", pictureControlName = "", modifiedAt = "")))
        assertEquals((1..9).toList(), slots.map { it.index })
        assertTrue(slots[4].occupied)
        assertFalse(slots[3].occupied)
    }

    // ---- 真实排布（真机：C002 不含槽位号，recipeIds 才带空洞） ----

    @Test
    fun `recipeIds keeps the hole left by a deletion`() {
        // 真机现象：删掉第 1 位后 C002 只剩 B、C 两条，按数组顺序编号就会显示成 1:B、2:C，
        // 而相机上实际是 2:B、3:C，第 1 位空着。
        val local = listOf(
            CloudSlot(index = 1, recipeId = "B", name = "Vitality Film", pictureControlName = "VitalityFilm_Pmango"),
            CloudSlot(index = 2, recipeId = "C", name = "Teal&Orange", pictureControlName = "ClrMode_TealOr_01a"),
        )
        val slots = mergeSlots(listOf(null, "B", "C", null, null, null, null, null, null), local)
        assertEquals(9, slots.size)
        assertFalse(slots[0].occupied)
        assertEquals("B", slots[1].recipeId)
        assertEquals("Vitality Film", slots[1].name)
        assertEquals("C", slots[2].recipeId)
        assertEquals(3, slots[2].index)
    }

    @Test
    fun `recipeIds wins over array order`() {
        val local = listOf(CloudSlot(index = 1, recipeId = "A", name = "A", pictureControlName = "pc"))
        val slots = mergeSlots(listOf(null, null, "A"), local)
        assertEquals(9, slots.size)
        assertEquals("A", slots[2].recipeId)
        assertFalse(slots[0].occupied)
    }

    @Test
    fun `missing layout falls back to array order`() {
        val local = listOf(
            CloudSlot(index = 1, recipeId = "A", name = "A", pictureControlName = "pc"),
            CloudSlot(index = 2, recipeId = "B", name = "B", pictureControlName = "pc"),
        )
        val slots = mergeSlots(null, local)
        assertEquals(9, slots.size)
        assertEquals("A", slots[0].recipeId)
        assertEquals("B", slots[1].recipeId)
    }

    @Test
    fun `all slots deleted yields nine empties`() {
        val slots = mergeSlots(List(9) { null }, emptyList())
        assertEquals(9, slots.size)
        assertTrue(slots.none { it.occupied })
    }

    @Test
    fun `parseLayouts reads recipeIds per camera`() {
        val raw = """
            {"statusCode":200,"body":{
              "camera":[{"deviceId":"01JTC8","name":"Zf","recipeIds":[null,"01JTC96K","01JVC"]}],
              "recipe":[{"id":"01JTC96K","name":"Sky Mist"}]
            }}
        """.trimIndent()
        val layouts = client().parseLayouts(raw)
        assertEquals(listOf(null, "01JTC96K", "01JVC"), layouts["01JTC8"])
    }

    @Test
    fun `parseLayouts tolerates a response without camera layout`() {
        assertTrue(client().parseLayouts("""{"statusCode":200,"body":{"recipe":[]}}""").isEmpty())
    }

    // ---- 我的书 / 收藏（IF_FR100_H05 / IF_IR_R_PR001 / IF_IR_R_PB002） ----

    @Test
    fun `my recipes reads books with capacity info`() {
        val raw = """
            {"statusCode":200,"body":{
              "authority":1,
              "books":[{"id":"BOOK1","label":"我的收藏","recipeCount":3},{"id":"BOOK2","name":"旅行"}],
              "allLocalRecipes":[{"id":"A"},{"id":"B"}],
              "cameras":[]
            }}
        """.trimIndent()
        val mine = client().parseMyRecipes(raw)
        assertEquals(2, mine.books.size)
        assertEquals("BOOK1", mine.books[0].id)
        assertEquals("我的收藏", mine.books[0].label)
        assertEquals(3, mine.books[0].recipeCount)
        assertEquals("旅行", mine.books[1].label)
        assertEquals(0, mine.books[1].recipeCount)
        assertEquals(2, mine.recipeCount)
        assertEquals(1, mine.authority)
    }

    @Test
    fun `my recipes without books yields empty list not an error`() {
        val mine = client().parseMyRecipes("""{"statusCode":200,"body":{"localRecipes":[]}}""")
        assertTrue(mine.books.isEmpty())
        assertEquals(0, mine.recipeCount)
    }

    @Test
    fun `favorite result collects created recipe ids`() {
        val raw = """{"statusCode":200,"body":{"bookId":"BOOK1","recipeIds":["L1","L2"]}}"""
        assertEquals(listOf("L1", "L2"), client().parseFavoriteResult(raw))
        // 没有 recipeIds 时不要炸：Web 端也只判断"有没有返回值"
        assertTrue(client().parseFavoriteResult("""{"statusCode":200,"body":{}}""").isEmpty())
    }

    @Test
    fun `created book id is required`() {
        assertEquals("BOOK9", client().parseCreatedBookId("""{"statusCode":200,"body":{"id":"BOOK9"}}"""))
        assertThrows(CloudException::class.java) {
            client().parseCreatedBookId("""{"statusCode":200,"body":{"name":"x"}}""")
        }
    }

    @Test
    fun `favorite and book bodies match the web payloads`() {
        assertEquals(
            """{"bookId":"BOOK1","recipeIds":["A","B"]}""",
            client().buildFavoriteBody("BOOK1", listOf("A", "", "B")),
        )
        assertEquals("""{"name":"NiKConnect"}""", client().buildBookBody("NiKConnect"))
    }

    @Test
    fun `moveSlot renumbers index so planSlotWrite sees the change`() {
        val before = slotsOf("A", "", "B", "", "", "", "", "", "")
        val after = moveSlot(before, 2, 0)

        assertEquals("B", after[0].recipeId.orEmpty())
        assertEquals("A", after[1].recipeId.orEmpty())
        assertEquals(listOf(1, 2, 3), after.take(3).map { it.index })

        // 只换顺序不改 index 时会被判成 noop，这正是真机上「显示排布没有变化」的根因
        assertTrue(planSlotWrite(before, before).noop)

        val plan = planSlotWrite(before, after)
        assertFalse(plan.noop)
        assertEquals(
            listOf(CloudSlotAssignment(1, "B"), CloudSlotAssignment(2, "A")),
            plan.assignments,
        )
    }

    @Test
    fun `moveSlot keeps empty slots and shifts them along`() {
        val before = slotsOf("A", "", "", "B", "", "", "", "", "")
        val after = moveSlot(before, 0, 2)

        assertEquals(listOf("", "", "A", "B"), after.take(4).map { it.recipeId.orEmpty() })
        assertEquals(listOf(3, 4), after.filter { !it.recipeId.isNullOrBlank() }.map { it.index })
        assertEquals(
            listOf(CloudSlotAssignment(3, "A"), CloudSlotAssignment(4, "B")),
            planSlotWrite(before, after).assignments,
        )
    }

    // ---- 收藏夹与方案副本（IF_FR100_H05） ----

    @Test
    fun `my recipes groups owned recipes by book`() {
        val raw = """
            {"authority":0,"books":[{"id":"BK1","label":"NiKConnect","recipeCount":2}],
             "allLocalRecipes":[
               {"id":"R1","name":"Vitality Film","pictureControlName":"VitalityFilm_Pmango","bookId":"BK1"},
               {"id":"R2","name":"Honey Tale","pictureControlName":"HoneyTale_MASAYA","bookId":"BK1"},
               {"id":"R3","name":"Sky Mist","pictureControlName":"SkyMistFilm_hinano"}]}
        """.trimIndent()
        val mine = client().parseMyRecipes(raw)
        assertEquals(1, mine.books.size)
        assertEquals("NiKConnect", mine.books.first().label)
        assertEquals(3, mine.recipes.size)
        assertEquals(3, mine.recipeCount)
        assertEquals("BK1", mine.recipes[0].bookId)
        assertNull(mine.recipes[2].bookId)
        assertEquals("VitalityFilm_Pmango", mine.recipes[0].pictureControlName)
    }

    @Test
    fun `my recipes found under nested recipe key`() {
        val raw = """
            {"authority":0,"bookList":[{"id":"BK9","bookName":"书"}],
             "body":{"recipe":[{"id":"R7","name":"Japanesque","bookId":"BK9"}]}}
        """.trimIndent()
        val mine = client().parseMyRecipes(raw)
        assertEquals(1, mine.recipes.size)
        assertEquals("Japanesque", mine.recipes.first().name)
        assertEquals("BK9", mine.recipes.first().bookId)
    }

    @Test
    fun `my recipes without books still reports count`() {
        val raw = """{"authority":0,"recipes":[{"id":"R1","name":"A"},{"id":"R2","name":"B"}]}"""
        val mine = client().parseMyRecipes(raw)
        assertTrue(mine.books.isEmpty())
        assertEquals(2, mine.recipes.size)
    }
}
