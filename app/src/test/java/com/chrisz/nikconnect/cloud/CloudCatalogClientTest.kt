package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CloudCatalogClient 纯 JVM 测试：只测目录/详情响应解析（网络调用不在此层测）。
 */
class CloudCatalogClientTest {

    private fun client() = CloudCatalogClient()

    @Test
    fun `parses catalog with covers`() {
        val raw = """
            {"recipe":[
              {"id":"01JAY6","name":"NegativeFilm Red&Cyan 01b","picture_controll_name":"Fuji_ClassicNega",
               "img":{"url":"https://front.ir.imagingcloud.nikon.com.cn/x/1.JPG?Policy=abc"},
               "public_date":"2024-11-01","is_new":true,"like":{"num":37,"status":"0"}},
              {"id":"01JAY7","name":"MOSS","picture_controll_name":"MOSS","like":{"num":"5"}}
            ]}
        """.trimIndent()
        val result = client().parseCatalog(raw)
        assertEquals(2, result.size)
        assertEquals("01JAY6", result[0].id)
        assertEquals("Fuji_ClassicNega", result[0].pictureControlName)
        assertEquals("https://front.ir.imagingcloud.nikon.com.cn/x/1.JPG?Policy=abc", result[0].coverUrl)
        assertEquals("2024-11-01", result[0].publicDate)
        assertTrue(result[0].isNew)
        assertEquals(37, result[0].likes)
        // 无日期/封面的条目走默认值
        assertEquals("", result[1].publicDate)
        assertNull(result[1].coverUrl)
        assertEquals(5, result[1].likes)
        assertEquals(false, result[1].isNew)
    }

    @Test
    fun `catalog without img keeps cover null`() {
        val raw = """{"recipe":[{"id":"a","name":"NoCover","picture_controll_name":"X"}]}"""
        val result = client().parseCatalog(raw)
        assertNull(result[0].coverUrl)
    }

    @Test
    fun `catalog skips entries without id`() {
        val raw = """{"recipe":[{"name":"no-id"},{"id":"ok","name":"HasId"}]}"""
        val result = client().parseCatalog(raw)
        assertEquals(1, result.size)
        assertEquals("ok", result[0].id)
    }

    @Test
    fun `catalog unwraps body envelope and surfaces error code`() {
        val ok = """{"statusCode":200,"body":{"recipe":[{"id":"a","name":"A"}]}}"""
        assertEquals(1, client().parseCatalog(ok).size)

        val limited = """{"statusCode":200,"body":{"error_code":"E01_0701"}}"""
        try {
            client().parseCatalog(limited)
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertEquals("E01_0701", e.errorCode)
        }
    }

    @Test
    fun `parses detail params exif and neighbors`() {
        val raw = """
            {"id":"01JAY6","name":"NegativeFilm Red&Cyan 01b","creatorName":"Nikon",
             "recipeDetail":"暖调负片质感，适合日落。",
             "thumbnail":"https://x/t.jpg",
             "pictureControl":{"settingName":"Fuji_ClassicNega","baseName":"FLEXIBLE COLOR",
               "quickSharp":"-1.00","sharpening":"+2.00","middleRangeSharp":"-0.50","clarity":"+1.00",
               "contrast":"+1.50","saturation":"-0.25","highlight":"-1.00","shadow":"+2.00",
               "whiteLevel":"-0.75","blackLevel":"+0.50"},
             "exif":{"model":"NIKON Z 8","exposureTime":"1/250","fNumber":"f/ 2.8","iso":"ISO 400",
               "focalLength":"50 mm","whiteBalance":"AUTO1","exposureBiasValue":"+0.3","exposureMode":"Manual",
               "meteringMode":"Multi-pattern","lensModel":"NIKKOR Z 50mm f/1.8 S"},
             "camera":"NIKON Z 8","lens":"NIKKOR Z 50mm f/1.8 S",
             "previous_recipe":{"id":"p1","name":"Prev"},
             "next_recipe":{"id":"n1","name":"Next"}}
        """.trimIndent()
        val d = client().parseDetail(raw)
        assertEquals("01JAY6", d.id)
        assertEquals("Nikon", d.author)
        assertEquals("FLEXIBLE COLOR", d.baseName)
        assertEquals("暖调负片质感，适合日落。", d.description)
        assertEquals("https://x/t.jpg", d.coverUrl)
        assertEquals(10, d.params.size)
        assertEquals(CloudParam("快速锐化", "-1.00"), d.params[0])
        assertEquals(CloudParam("黑色色阶", "+0.50"), d.params[9])
        assertEquals("快门", d.exif.first().label)
        assertEquals("1/250", d.exif.first().value)
        assertEquals("p1", d.previousId)
        assertEquals("Next", d.nextName)
        assertEquals("NIKON Z 8", d.camera)
    }

    @Test
    fun `detail skips unset params and falls back to memo`() {
        val raw = """
            {"id":"x","name":"X","recipeMemo":"备忘录正文",
             "pictureControl":{"settingName":"S","baseName":"MONOCHROME","contrast":"+1.00",
               "saturation":"-","clarity":""},
             "exif":{"iso":"0","fNumber":"f/ 4"}}
        """.trimIndent()
        val d = client().parseDetail(raw)
        assertEquals("备忘录正文", d.description)
        assertEquals(1, d.params.size)
        assertEquals("对比度", d.params[0].label)
        // iso 为 "0" 视为未设置
        assertEquals(1, d.exif.size)
        assertEquals("光圈", d.exif[0].label)
        assertNull(d.previousId)
        assertNull(d.nextId)
    }

    @Test
    fun `detail exposes morning base params instead of hiding them`() {
        // 真机/官网实测：baseName=MORNING 的方案（Bluegrain_jyota）用的是另一组字段
        val raw = """
            {"id":"01KH8Q3G1M2EHCJST14GS5Q7VR","name":"Bluegrain by jyota tomonori",
             "pictureControlName":"Bluegrain_jyota",
             "pictureControl":{"settingName":"Bluegrain_jyota","baseName":"MORNING",
               "quickSharp":"-","effectLevel":"50","sharpening":"+2.75","middleRangeSharp":"+0.50",
               "clarity":"+0.75","contrast":"0.00","brightness":"-","saturation":"+3.00","hue":"-3.00",
               "filterEffect":"-","toning":"-","toningSaturation":"-"}}
        """.trimIndent()
        val detail = client().parseDetail(raw)
        val labels = detail.params.map { it.label }
        assertTrue(labels.contains("效果级别"))
        assertTrue(labels.contains("色相"))
        assertEquals("50", detail.params.first { it.label == "效果级别" }.value)
        assertEquals("-3.00", detail.params.first { it.label == "色相" }.value)
        // 未设置的 `-` 仍不显示
        assertTrue(labels.none { it == "亮度" || it == "调色" })
    }

    @Test
    fun `detail throws on invalid json`() {
        try {
            client().parseDetail("""{"id": "broken""")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("不是合法 JSON"))
        }
    }

    @Test
    fun `detail throws when id missing`() {
        try {
            client().parseDetail("""{"name":"no id"}""")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("缺少 id"))
        }
    }
}
