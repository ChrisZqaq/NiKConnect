package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 账号 guid 的提取规则。
 *
 * 回归背景：0.6 首批在真机上 `IF_IR_R_C001` 返回 422——当时 `guidFromUrl` 取 URL 里第一个 ULID，
 * 把方案详情地址 `/publicrecipe/0000000000000001/{recipeId}` 里的**方案 ID** 当成了账号 guid。
 */
class CloudIdentifiersTest {

    private val account = "01HXDNNTHY1AZ6RVMMXSXY3BTK"
    private val recipe = "01JAY6CBEA2GQMKWN9EW7GHXF8"

    @Test
    fun `camera path yields account guid`() {
        assertEquals(
            account,
            guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/camera/$account/"),
        )
        assertEquals(
            account,
            guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/camera/$account"),
        )
        assertEquals(
            account,
            guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/camera/$account/C123"),
        )
    }

    @Test
    fun `recipe detail path never leaks recipe id`() {
        // 真实地址形状：固定 guid 段 0000000000000001 + 方案 id
        assertNull(
            guidFromUrl(
                "https://api.user.ir.imagingcloud.nikon.com.cn/publicrecipe/0000000000000001/" +
                    "$recipe?sortType=0&isPagenation=false",
            ),
        )
        assertNull(guidFromUrl("https://imagingcloud.nikon.com.cn/recipe/release/detail/$recipe"))
    }

    @Test
    fun `publicrecipe with account guid still works`() {
        assertEquals(
            account,
            guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/publicrecipe/$account"),
        )
        assertEquals(
            account,
            guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/publicbook/$account"),
        )
    }

    @Test
    fun `query and fragment guid are accepted`() {
        assertEquals(account, guidFromUrl("https://imagingcloud.nikon.com.cn/recipe/camera?guid=$account"))
        assertEquals(account, guidFromUrl("https://imagingcloud.nikon.com.cn/cb#userGuid=$account"))
        assertNull(guidFromUrl("https://imagingcloud.nikon.com.cn/cb?guid=not-a-guid"))
    }

    @Test
    fun `uuid form is accepted`() {
        val uuid = "9f8e7d6c-5b4a-3210-9f8e-7d6c5b4a3210"
        assertEquals(uuid, guidFromUrl("https://api.user.ir.imagingcloud.nikon.com.cn/camera/$uuid/"))
    }

    @Test
    fun `unrelated url yields nothing`() {
        assertNull(guidFromUrl("https://api.user.cwp.imagingcloud.nikon.com.cn/bff/recipe/release"))
        assertNull(guidFromUrl("https://imagingcloud.nikon.com.cn/recipe/index/"))
        assertNull(guidFromUrl(""))
        assertNull(guidFromUrl(null))
    }
}
